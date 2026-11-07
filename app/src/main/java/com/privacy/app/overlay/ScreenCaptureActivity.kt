package com.privacy.app.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Invisible activity that asks for Android's screen-capture consent and hands the result to [OverlayService]. */
class ScreenCaptureActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 1)
    }
    override fun onActivityResult(req: Int, code: Int, data: Intent?) {
        super.onActivityResult(req, code, data)
        startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_PROJECTION).putExtra("code", code).putExtra("data", data))
        finish()
    }
}
