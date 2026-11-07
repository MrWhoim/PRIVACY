package com.privacy.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privacy.app.ui.MainViewModel
import com.privacy.app.ui.ScreenBackground
import com.privacy.app.ui.ScreenHeader
import com.privacy.app.ui.SegmentedTabs
import com.privacy.app.ui.SoftCard
import com.privacy.app.ui.goTo
import com.privacy.app.ui.health.ProfileSheet
import com.privacy.app.ui.vault.VaultBody
import kotlinx.coroutines.launch

/** Account = the vault. Inside it (after the passcode): assistant, your private sections, Memory, health plan, Telegram and backup. */
@Composable
fun AccountScreen(vm: MainViewModel, onBack: () -> Unit) {
    ScreenBackground {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader("Account", "Vault · Memory · health plan · Telegram · backup", onBack = onBack)
            Box(Modifier.weight(1f)) { VaultBody(vm) }
        }
    }
}

@Composable
internal fun HealthPlanCard(vm: MainViewModel) {
    val profile by vm.g.health.profile.collectAsStateWithLifecycle()
    var editPlan by remember { mutableStateOf(false) }
    SoftCard {
        CardTitle(Icons.Rounded.Favorite, "Health plan", "Your age, height, weight, goal, diet, allergies, sleep and water goals. They personalise your calorie, protein, carb, fat, sleep and water targets.")
        Text(
            if (profile.isEmpty) "Not set up yet – targets use general reference values until you add your details."
            else "Age ${profile.age ?: "—"} · ${profile.heightCm?.let { "${it.toInt()} cm" } ?: "height —"} · ${profile.weightKg?.let { "${it.toInt()} kg" } ?: "weight —"} · ${profile.goal.label}",
            fontSize = 13.sp
        )
        OutlinedButton(onClick = { editPlan = true }) { Text("Edit my plan") }
    }
    if (editPlan) ProfileSheet(vm, profile) { editPlan = false }
}
