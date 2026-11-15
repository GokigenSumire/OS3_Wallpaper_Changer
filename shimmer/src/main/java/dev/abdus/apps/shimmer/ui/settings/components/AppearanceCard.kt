package dev.abdus.apps.shimmer.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import dev.abdus.apps.shimmer.ui.components.BlurredCard
import dev.abdus.apps.shimmer.ui.theme.Spacing
import dev.abdus.apps.shimmer.ui.theme.ThemeMode

@Composable
fun AppearanceCard(themeMode: ThemeMode, onThemeModeSelected: (ThemeMode) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    BlurredCard(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.m, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text("外观", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text("主题", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                TextButton(onClick = { expanded = true }) {
                    Text(themeMode.label)
                    Icon(Icons.Default.ExpandMore, contentDescription = "选择主题")
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    ThemeMode.entries.forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.label) }, onClick = {
                            onThemeModeSelected(mode); expanded = false
                        }, leadingIcon = { RadioButton(selected = themeMode == mode, onClick = null) },
                            modifier = Modifier.semantics { role = Role.RadioButton; selected = themeMode == mode })
                    }
                }
            }
        }
    }
}
