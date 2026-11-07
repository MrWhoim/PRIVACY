package com.privacy.app.ai

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock

/** Things the assistant can do on the phone itself. Android only lets an app open other apps while it is on screen. */
object DeviceTools {
    private fun launch(ctx: Context, i: Intent): String = try {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        "ok"
    } catch (e: Exception) {
        "Android refused to open it (${e.message ?: "no app can handle it"}). Apps can only open other apps while this app is on screen."
    }

    fun openApp(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(main, 0)
            .map { it to it.loadLabel(pm).toString() }
            .filter { it.second.contains(name.trim(), ignoreCase = true) }
            .minByOrNull { it.second.length }
            ?: return "No installed app matches \"$name\"."
        val intent = pm.getLaunchIntentForPackage(match.first.activityInfo.packageName) ?: return "Can't open ${match.second}."
        val r = launch(ctx, intent)
        return if (r == "ok") "Opened ${match.second}." else r
    }

    fun openUrl(ctx: Context, url: String): String {
        val u = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val r = launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
        return if (r == "ok") "Opened $u." else r
    }

    fun setAlarm(ctx: Context, hour: Int, minute: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val r = launch(ctx, i)
        return if (r == "ok") "Alarm set for %02d:%02d in the clock app.".format(hour, minute) else r
    }

    fun setTimer(ctx: Context, seconds: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_MESSAGE, label).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val r = launch(ctx, i)
        return if (r == "ok") "Timer started for $seconds seconds." else r
    }

    fun dial(ctx: Context, number: String): String {
        val r = launch(ctx, Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        return if (r == "ok") "Opened the dialer with $number (you press call)." else r
    }

    fun flashlight(ctx: Context, on: Boolean): String = try {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: return "This phone has no flashlight."
        cm.setTorchMode(id, on)
        if (on) "Flashlight on." else "Flashlight off."
    } catch (e: Exception) {
        "Couldn't change the flashlight: ${e.message}"
    }

    /** A phone number, or a contact's name looked up in the phone book. Calls straight away when the Phone permission is allowed, otherwise opens the dialer. */
    fun call(ctx: Context, target: String): String {
        val t = target.trim()
        if (t.isEmpty()) return "Who should I call?"
        val digits = t.filter { it.isDigit() || it == '+' }
        val isNumber = digits.count { it.isDigit() } >= 3 && t.count { it.isLetter() } == 0
        val number: String
        var who = t
        if (isNumber) number = digits else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            {
                val shown = UiBridge.send(UiCommand.RequestPermission("phone"))
                return if (shown) "I need permission to read your contacts and make calls – I've put the prompt on your screen. Tap Allow, then ask me to call again."
                else "I need the Phone and Contacts permission to look up \"$t\". Open the app (menu → Settings → Permissions) and allow it, or give me the number."
            }
            val found = LinkedHashMap<String, String>() // number → name
            runCatching {
                ctx.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER),
                    "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$t%"), null
                )?.use { c ->
                    while (c.moveToNext()) { val n = (c.getString(1) ?: "").filter { it.isDigit() || it == '+' }; if (n.isNotEmpty()) found.putIfAbsent(n.takeLast(10), c.getString(0) + "|" + n) }
                }
            }
            if (found.isEmpty()) return "No contact matches \"$t\". Ask the user for the number."
            val names = found.values.map { it.substringBefore('|') }.distinct()
            if (names.size > 1) return "Several contacts match: ${names.take(6).joinToString()}. Ask the user which one."
            val pick = found.values.first()
            who = pick.substringBefore('|'); number = pick.substringAfter('|')
        }
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val r = launch(ctx, Intent(if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))))
        if (r == "ok" && !granted) UiBridge.send(UiCommand.RequestPermission("phone"))
        return if (r != "ok") r else if (granted) "Calling $who ($number)." else "Opened the dialer for $who ($number). Allow the Phone permission when asked and I can call directly next time."
    }
}
