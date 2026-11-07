package com.privacy.app.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Small, boring, standard crypto: PBKDF2-HMAC-SHA256 for keys, AES-256-GCM for data. */
object Crypto {
    const val PBKDF2_ITERATIONS = 600_000
    private val rng = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    /** The provider's own PBKDF2 (pure-Java on Android – correct but slow with 600k rounds). Kept as the reference/fallback. */
    fun pbkdf2Provider(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Same PBKDF2-HMAC-SHA256 (identical output, same iteration count, same security) but built on the platform's native HMAC,
     * which is several times faster than the pure-Java provider. Passwords are encoded as UTF-8, exactly like the provider.
     * One 32-byte block is all a 256-bit key needs.
     */
    fun pbkdf2Fast(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val pw = String(password).toByteArray(Charsets.UTF_8)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(pw, "HmacSHA256"))
            mac.update(salt)
            mac.update(byteArrayOf(0, 0, 0, 1))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            return t
        } finally {
            java.util.Arrays.fill(pw, 0)
        }
    }

    /** Default entry point: fast path. */
    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray =
        runCatching { pbkdf2Fast(password, salt, iterations) }.getOrElse { pbkdf2Provider(password, salt, iterations) }

    /** Returns iv (12 bytes) + ciphertext + tag. */
    fun encrypt(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = randomBytes(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    /** Throws if the key is wrong or the data was modified. */
    fun decrypt(key: ByteArray, blob: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = blob.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        if (aad != null) cipher.updateAAD(aad)
        return cipher.doFinal(blob, 12, blob.size - 12)
    }
}
