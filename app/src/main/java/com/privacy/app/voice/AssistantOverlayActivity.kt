package com.privacy.app.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacy.app.PrivacyApp
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.face.FaceScreen
import com.privacy.app.ui.face.LocalAssistantFemale
import com.privacy.app.ui.theme.PrivacyTheme

/**
 * Opened by the 1×1 mic widget. A see-through window with only the pixel assistant on it: it listens right away, answers out loud
 * and can do everything the app can (tasks, notes, reminders, tools …) because it uses the same assistant behind the scenes.
 */
class AssistantOverlayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as PrivacyApp).graph
        setContent {
            val fontKey by graph.settings.fontKey.collectAsStateWithLifecycle()
            val aGender by graph.settings.assistantGender.collectAsStateWithLifecycle()
            val aName by graph.settings.assistantName.collectAsStateWithLifecycle()
            val female = GroqSpeech.genderOf(aGender, aName) == "female"
            PrivacyTheme(darkTheme = true, fontKey = fontKey) {
                CompositionLocalProvider(LocalAssistantFemale provides female) {
                    val vm: MainViewModel = viewModel()
                    FaceScreen(vm, autoListen = true, overlay = true, onClose = { finish() })
                }
            }
        }
    }
}
