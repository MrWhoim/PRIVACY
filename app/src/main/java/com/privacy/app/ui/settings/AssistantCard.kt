package com.privacy.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.SoftCard

// ============================ Assistant + memories ============================
@Composable
internal fun AssistantCard(vm: MainViewModel) {
    val settings = vm.g.settings
    val myName by settings.userName.collectAsStateWithLifecycle()
    val myGender by settings.userGender.collectAsStateWithLifecycle()
    val call by settings.userCall.collectAsStateWithLifecycle()
    val aName by settings.assistantName.collectAsStateWithLifecycle()
    val gender by settings.assistantGender.collectAsStateWithLifecycle()
    val extra by settings.extraInstructions.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    SoftCard {
        CardTitle(Icons.Rounded.Psychology, "Assistant", "Your name, its name and voice, and your standing instructions. Private – only visible after your passcode.")
        OutlinedTextField(
            value = myName, onValueChange = settings::setUserName, label = { Text("Your name") },
            supportingText = { Text("Used in greetings. The assistant addresses you as $call.", fontSize = 11.sp) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("male" to "Male · Sir", "female" to "Female · Ma'am").forEach { (v, label) ->
                FilterChip(selected = myGender == v, onClick = { settings.setUserGender(v) }, label = { Text(label) })
            }
        }
        OutlinedTextField(
            value = aName, onValueChange = settings::setAssistantName, label = { Text("Assistant name") },
            supportingText = { Text("The voice follows the name (a female name → female voice). Change it any time.", fontSize = 11.sp) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Text("Voice", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Follow the name", "male" to "Male", "female" to "Female").forEach { (v, label) ->
                FilterChip(selected = gender == v, onClick = { settings.setAssistantGender(v) }, label = { Text(label) })
            }
        }
        OutlinedTextField(
            value = extra, onValueChange = settings::setExtra, label = { Text("Extra instructions for the assistant") },
            placeholder = { Text("e.g. Be brief. Answer in Hinglish. Push me to finish my top task first.") },
            minLines = 2, modifier = Modifier.fillMaxWidth()
        )
    }
}

