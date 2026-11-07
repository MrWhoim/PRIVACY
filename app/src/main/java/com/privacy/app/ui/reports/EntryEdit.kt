package com.privacy.app.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privacy.app.data.HealthEntryEntity
import com.privacy.app.data.fmt
import com.privacy.app.ui.MainViewModel

/** Edit or delete one saved health entry. Used by the Health tab and the reports, so a correction changes the same row everywhere. */
@Composable
fun EntryEditDialog(vm: MainViewModel, e: HealthEntryEntity, onDismiss: () -> Unit) {
    var label by remember(e.id) { mutableStateOf(e.label) }
    var amount by remember(e.id) { mutableStateOf(e.amount?.let { fmt(it) } ?: "") }
    var kcal by remember(e.id) { mutableStateOf(e.kcal?.let { fmt(it) } ?: "") }
    var protein by remember(e.id) { mutableStateOf(e.protein?.let { fmt(it) } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit entry") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("What") }, singleLine = true)
                if (e.category != "FOOD") OutlinedTextField(amount, { amount = it }, label = { Text("Amount (" + (e.unit ?: "") + ")") }, singleLine = true)
                if (e.category == "FOOD") {
                    OutlinedTextField(kcal, { kcal = it }, label = { Text("Calories (kcal)") }, singleLine = true)
                    OutlinedTextField(protein, { protein = it }, label = { Text("Protein (g)") }, singleLine = true)
                }
                if (e.estimated) Text("These numbers are an estimate. Saving makes them yours (confirmed).", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.originalText.isNotBlank()) Text("You said: “" + e.originalText + "”", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (e.history.isNotBlank()) Text("History:\n" + e.history.trim(), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.editHealth(e.id, label.takeIf { it.isNotBlank() }, amount.toDoubleOrNull(), kcal.toDoubleOrNull(), protein.toDoubleOrNull(), confirm = true)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.deleteHealth(e.id); onDismiss() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
