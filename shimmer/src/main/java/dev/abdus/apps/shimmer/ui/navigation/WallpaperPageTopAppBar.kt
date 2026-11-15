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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import dev.abdus.apps.shimmer.ui.effects.GlassEffectDefaults
import dev.abdus.apps.shimmer.p10.WallpaperTarget
import dev.abdus.apps.shimmer.ui.onboarding.GuideAnchor
import dev.abdus.apps.shimmer.ui.onboarding.guideAnchor

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
            Box(Modifier.width(96.dp).padding(start = 12.dp), contentAlignment = Alignment.CenterStart) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        title = {
            // Same visual tokens as FloatingBar: capsule, 48dp, 4dp inset and a moving indicator.
            val activeTarget = header?.target ?: wallpaperTarget
            val localIndicator by animateFloatAsState(activeTarget.ordinal.toFloat(),
                animationSpec = spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow),
                label = "wallpaper-target-pill")
            val indicator = LocalWallpaperPillProgress.current ?: localIndicator
            BoxWithConstraints(Modifier.widthIn(max = 140.dp).height(48.dp)
                .guideAnchor(GuideAnchor.TARGET, targetEnabled && header != null)
                .clip(CircleShape).background(GlassEffectDefaults.bottomBarContainerColor()).padding(4.dp)) {
                val segmentWidth = maxWidth / WallpaperTarget.entries.size
                Box(Modifier.offset(x = segmentWidth * indicator).width(segmentWidth).fillMaxHeight()
                    .clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)))
                Row(Modifier.fillMaxSize().selectableGroup()) {
                    WallpaperTarget.entries.forEach { target ->
                        val selected = activeTarget == target
                        val contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        Row(Modifier.weight(1f).fillMaxHeight()
                            .selectable(selected, interactionSource = remember { MutableInteractionSource() },
                                indication = null, enabled = targetEnabled && header != null,
                                role = Role.RadioButton, onClick = { header?.onSelected?.invoke(target) })
                            .semantics {
                                contentDescription = WallpaperTargetUi.label(target)
                                stateDescription = if (selected) "当前目标" else "切换目标"
                            }, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center) {
                            if (selected) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = contentColor)
                            Text(WallpaperTargetUi.label(target), style = MaterialTheme.typography.labelLarge,
                                color = contentColor, maxLines = 1)
                        }
                    }
                }
            }
        },
        actions = { Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.End, content = actions) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    )
}
