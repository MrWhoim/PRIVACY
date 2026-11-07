package com.privacy.app.security

import android.content.Context
import android.util.Base64
import com.privacy.app.data.VaultEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class VaultCategory(val label: String, val hint: String) {
    PROFILE("About me", "Name, birthday, health goals, work, family…"),
    GOAL("Future goals", "What you want to achieve and by when"),
    DESIRE("Desires & wishlist", "Things you want to buy, try, visit, become"),
    CREDENTIALS("Username & Password", "Logins you want to keep safe – title = site or app, text = username and password"),
    PRIVATE("Philosophy", "Any thought you have about life – what you believe, learned, or want to remember")
}

data class VaultItem(
    val id: Long,
    val category: VaultCategory,
    val title: String,
    val body: String,
    val share: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

enum class UnlockResult { OK, WRONG, LOCKED_OUT }

/**
 * Passcode design
 *  - A random 256-bit data key (DEK) encrypts every vault entry (AES-GCM).
 *  - The DEK itself is stored only "wrapped" by a key derived from your passcode (PBKDF2, 600k rounds).
 *  - Without the passcode there is nothing on the phone that can decrypt the vault.
 *  - Forgot passcode = data is unrecoverable (by design).
 */
class VaultManager(context: Context) {
    private val prefs = context.getSharedPreferences("dh_vault", Context.MODE_PRIVATE)
    private val aad = "privacy-vault-dek".toByteArray()
    private val entryAad = "privacy-vault-entry".toByteArray()

    @Volatile private var dek: ByteArray? = null
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    val isSetUp: Boolean get() = prefs.contains("wrapped")
    val isUnlocked: Boolean get() = dek != null

    fun lockedOutMs(): Long = (prefs.getLong("locked_until", 0) - System.currentTimeMillis()).coerceAtLeast(0)

    /** CPU heavy (PBKDF2) – call from a background dispatcher. */
    fun setUp(pass: CharArray) {
        val salt = Crypto.randomBytes(16)
        val newDek = Crypto.randomBytes(32)
        val kek = Crypto.pbkdf2(pass, salt)
        val wrapped = Crypto.encrypt(kek, newDek, aad)
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .putInt("fails", 0).putLong("locked_until", 0)
            .apply()
        dek = newDek
        _unlocked.value = true
    }

    fun unlock(pass: CharArray): UnlockResult {
        if (lockedOutMs() > 0) return UnlockResult.LOCKED_OUT
        return try {
            dek = unwrap(pass)
            prefs.edit().putInt("fails", 0).putLong("locked_until", 0).apply()
            _unlocked.value = true
            UnlockResult.OK
        } catch (_: Exception) {
            val fails = prefs.getInt("fails", 0) + 1
            val editor = prefs.edit().putInt("fails", fails)
            if (fails >= 5) {
                val delay = minOf(3_600_000L, 30_000L shl (fails - 5).coerceAtMost(7))
                editor.putLong("locked_until", System.currentTimeMillis() + delay)
            }
            editor.apply()
            if (lockedOutMs() > 0) UnlockResult.LOCKED_OUT else UnlockResult.WRONG
        }
    }

    private fun unwrap(pass: CharArray): ByteArray {
        val salt = Base64.decode(prefs.getString("salt", "")!!, Base64.NO_WRAP)
        val wrapped = Base64.decode(prefs.getString("wrapped", "")!!, Base64.NO_WRAP)
        return try {
            Crypto.decrypt(Crypto.pbkdf2Fast(pass, salt), wrapped, aad)
        } catch (e: Exception) {
            // Plain-ASCII passcodes give identical keys on both paths, so a failure is a wrong passcode – answer immediately.
            // Only for non-ASCII passcodes do we double-check with the provider's implementation (never locks anyone out).
            if (pass.all { it.code < 128 }) throw e
            Crypto.decrypt(Crypto.pbkdf2Provider(pass, salt), wrapped, aad)
        }
    }

    fun lock() {
        dek = null
        _unlocked.value = false
    }

    /** Re-wraps the data key under a new passcode. Existing entries stay untouched. */
    fun changePasscode(old: CharArray, new: CharArray): Boolean = try {
        val key = unwrap(old)
        val salt = Crypto.randomBytes(16)
        val wrapped = Crypto.encrypt(Crypto.pbkdf2(new, salt), key, aad)
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("wrapped", Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .apply()
        dek = key
        _unlocked.value = true
        true
    } catch (_: Exception) {
        false
    }

    /** "Forgot passcode": removes the key material (caller must also delete the vault rows). */
    fun wipe() {
        prefs.edit().clear().apply()
        lock()
    }

    fun encryptItem(category: VaultCategory, title: String, body: String): ByteArray {
        val key = dek ?: error("Vault is locked")
        val json = JSONObject().put("c", category.name).put("t", title).put("b", body).toString()
        return Crypto.encrypt(key, json.toByteArray(), entryAad)
    }

    fun decryptItem(e: VaultEntity): VaultItem? {
        val key = dek ?: return null
        return try {
            val o = JSONObject(String(Crypto.decrypt(key, e.blob, entryAad)))
            VaultItem(
                id = e.id,
                category = runCatching { VaultCategory.valueOf(o.getString("c")) }.getOrDefault(VaultCategory.PRIVATE),
                title = o.optString("t"),
                body = o.optString("b"),
                share = e.share,
                createdAt = e.createdAt,
                updatedAt = e.updatedAt
            )
        } catch (_: Exception) {
            null
        }
    }
}
