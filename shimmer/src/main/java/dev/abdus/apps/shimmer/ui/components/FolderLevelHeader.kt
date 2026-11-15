package dev.abdus.apps.shimmer.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.abdus.apps.shimmer.ui.effects.GlassEffectDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Shared hierarchy and metadata layout for library and queue; actions never toggle disclosure. */
@Composable
fun FolderLevelHeader(name: String, count: Int, depth: Int, expanded: Boolean, expandable: Boolean = true,
                      state: String? = null, onToggle: () -> Unit,
                      modifier: Modifier = Modifier, nested: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(modifier.fillMaxWidth().padding(start = (14 * depth.coerceIn(0, 6)).dp),
        shape = MaterialTheme.shapes.medium,
        color = if (nested) Color.Transparent else GlassEffectDefaults.cardContainerColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 56.dp)
                .then(if (expandable) Modifier.clickable(role = Role.Button,
                    onClickLabel = if (expanded) "折叠文件夹" else "展开文件夹", onClick = onToggle) else Modifier)
                .semantics { if (expandable) stateDescription = if (expanded) "已展开" else "已折叠" }
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (expandable) Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                else Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(name.trimEnd('/'), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("$count 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!state.isNullOrBlank()) Surface(shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)) {
                            Text(state, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
            }
            actions()
        }
    }
}
