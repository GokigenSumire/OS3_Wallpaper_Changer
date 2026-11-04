package dev.abdus.apps.shimmer.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.abdus.apps.shimmer.p10.WallpaperTarget

/** MainNavigation owns selection; read-only back previews cannot change it. */
data class WallpaperHeaderState(val target: WallpaperTarget, val onSelected: (WallpaperTarget) -> Unit)
val LocalWallpaperHeader = staticCompositionLocalOf<WallpaperHeaderState?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WallpaperPageTopAppBar(
    title: String,
    wallpaperTarget: WallpaperTarget = WallpaperTarget.HOME,
    targetEnabled: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val header = LocalWallpaperHeader.current
    CenterAlignedTopAppBar(
        navigationIcon = {
            Box(Modifier.width(96.dp).padding(start = 16.dp), contentAlignment = Alignment.CenterStart) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            }
        },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                WallpaperTarget.entries.forEach { target ->
                    FilterChip(
                        selected = (header?.target ?: wallpaperTarget) == target,
                        enabled = targetEnabled && header != null,
                        onClick = { header?.onSelected?.invoke(target) },
                        modifier = Modifier.semantics { contentDescription = WallpaperTargetUi.label(target) },
                        label = { Text(target.name, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
                    )
                }
            }
        },
        actions = { Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.End, content = actions) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}
