package com.sakura.romance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RomanceDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        GlassPanel(Modifier.fillMaxWidth(), tint = PureWhite.copy(alpha = .62f), overContent = true) {
            Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
                ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
                Spacer(Modifier.height(20.dp))
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                Spacer(Modifier.height(24.dp))
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        dismissButton?.invoke()
                        Spacer(Modifier.width(8.dp))
                        confirmButton()
                    }
                }
            }
        }
    }
}
