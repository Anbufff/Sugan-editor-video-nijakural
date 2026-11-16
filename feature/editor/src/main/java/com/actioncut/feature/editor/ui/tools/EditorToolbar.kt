package com.actioncut.feature.editor.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.actioncut.core.designsystem.component.clickableNoRipple
import com.actioncut.core.designsystem.component.rememberHaptic
import com.actioncut.feature.editor.ui.EditorTool

/** Default ordering of tools in the bottom bar. */
val MainTools: List<EditorTool> = listOf(
    EditorTool.SPLIT,
    EditorTool.DELETE,
    EditorTool.ANIMATION,
    EditorTool.DURATION,
    EditorTool.MUTE,
    EditorTool.MUSIC,
    EditorTool.AUDIO,
    EditorTool.SUBSCRIBE,
    EditorTool.LT,
    EditorTool.PIP,
    EditorTool.NEWS_ALERT,
    EditorTool.ARROW,
    EditorTool.TRIM_R,
    EditorTool.CAPTURE,
    EditorTool.SPEED,
    EditorTool.DEL_VIDEOS,
    EditorTool.VOLUME,
    EditorTool.VOICEOVER,
    EditorTool.EXTRACT_AUDIO,
    EditorTool.FILTERS,
    EditorTool.ADJUST,
    EditorTool.CROP,
    EditorTool.CANVAS,
    EditorTool.TEXT,
    EditorTool.STICKER,
    EditorTool.TRANSITIONS,
    EditorTool.EFFECTS,
    EditorTool.KEYFRAME,
    EditorTool.REVERSE,
    EditorTool.ROTATE,
    EditorTool.SETTINGS,
)

@Composable
fun EditorToolbar(
    activeTool: EditorTool?,
    onToolSelected: (EditorTool) -> Unit,
    modifier: Modifier = Modifier,
    tools: List<EditorTool> = MainTools,
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(tools, key = { it.name }) { tool ->
            CompactColorfulToolItem(
                label = tool.label,
                icon = tool.icon(),
                accent = tool.accentColor(),
                selected = tool == activeTool,
                onClick = { onToolSelected(tool) },
            )
        }
    }
}

@Composable
private fun CompactColorfulToolItem(
    label: String,
    icon: ImageVector,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val haptic = rememberHaptic()
    val tileBg = if (selected) accent else accent.copy(alpha = 0.16f)
    val iconTint = if (selected) Color.Black else accent
    val borderCol = if (selected) Color.White else accent.copy(alpha = 0.35f)

    Column(
        modifier = Modifier
            .clickableNoRipple { haptic.tick(); onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tileBg)
                .border(1.dp, borderCol, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(18.dp))
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

fun EditorTool.accentColor(): Color = when (this) {
    EditorTool.SPLIT -> Color(0xFF00E5FF)
    EditorTool.DELETE -> Color(0xFFFF3B5C)
    EditorTool.ANIMATION -> Color(0xFFFFD700)
    EditorTool.DURATION -> Color(0xFFFF8C42)
    EditorTool.TRIM, EditorTool.TRIM_R -> Color(0xFF26C6DA)
    EditorTool.SPEED -> Color(0xFF76FF03)
    EditorTool.VOLUME -> Color(0xFF40C4FF)
    EditorTool.MUTE -> Color(0xFFFF5C8A)
    EditorTool.AUDIO -> Color(0xFFBA68C8)
    EditorTool.MUSIC -> Color(0xFFE040FB)
    EditorTool.VOICEOVER -> Color(0xFFFF6E40)
    EditorTool.SUBSCRIBE -> Color(0xFFFF1744)
    EditorTool.LT -> Color(0xFF18FFFF)
    EditorTool.PIP -> Color(0xFF00E676)
    EditorTool.NEWS_ALERT -> Color(0xFFFFB300)
    EditorTool.ARROW -> Color(0xFFFFE300)
    EditorTool.CAPTURE -> Color(0xFFFFEA00)
    EditorTool.DEL_VIDEOS -> Color(0xFFFF5252)
    EditorTool.EXTRACT_AUDIO -> Color(0xFF7C4DFF)
    EditorTool.REVERSE -> Color(0xFFFF7043)
    EditorTool.ROTATE -> Color(0xFF69F0AE)
    EditorTool.FILTERS -> Color(0xFFF06292)
    EditorTool.ADJUST -> Color(0xFF4FC3F7)
    EditorTool.CROP -> Color(0xFF00E676)
    EditorTool.CANVAS -> Color(0xFFB388FF)
    EditorTool.TEXT -> Color(0xFFFFCA28)
    EditorTool.STICKER -> Color(0xFFFFAB40)
    EditorTool.TRANSITIONS -> Color(0xFF64FFDA)
    EditorTool.EFFECTS -> Color(0xFF9E7BFF)
    EditorTool.KEYFRAME -> Color(0xFFEEFF41)
    EditorTool.SETTINGS -> Color(0xFF80D8FF)
}

fun EditorTool.icon(): ImageVector = when (this) {
    EditorTool.SPLIT -> Icons.Filled.ContentCut
    EditorTool.TRIM -> Icons.Filled.ContentCut
    EditorTool.TRIM_R -> Icons.Filled.Crop
    EditorTool.DELETE -> Icons.Filled.Delete
    EditorTool.ANIMATION -> Icons.Filled.ZoomOutMap
    EditorTool.DURATION -> Icons.Filled.Timer
    EditorTool.SPEED -> Icons.Filled.Speed
    EditorTool.VOLUME -> Icons.Filled.VolumeUp
    EditorTool.MUTE -> Icons.Filled.VolumeOff
    EditorTool.AUDIO -> Icons.Filled.LibraryMusic
    EditorTool.MUSIC -> Icons.Filled.MusicNote
    EditorTool.VOICEOVER -> Icons.Filled.Mic
    EditorTool.SUBSCRIBE -> Icons.Filled.PlayCircle
    EditorTool.LT -> Icons.Filled.Subtitles
    EditorTool.PIP -> Icons.Filled.PictureInPictureAlt
    EditorTool.NEWS_ALERT -> Icons.Filled.Campaign
    EditorTool.ARROW -> Icons.Filled.NearMe
    EditorTool.CAPTURE -> Icons.Filled.CameraAlt
    EditorTool.DEL_VIDEOS -> Icons.Filled.DeleteSweep
    EditorTool.EXTRACT_AUDIO -> Icons.Filled.CallSplit
    EditorTool.REVERSE -> Icons.Filled.FastRewind
    EditorTool.ROTATE -> Icons.Filled.RotateRight
    EditorTool.FILTERS -> Icons.Filled.FilterVintage
    EditorTool.ADJUST -> Icons.Filled.Tune
    EditorTool.CROP -> Icons.Filled.Crop
    EditorTool.CANVAS -> Icons.Filled.AspectRatio
    EditorTool.TEXT -> Icons.Filled.TextFields
    EditorTool.STICKER -> Icons.Filled.EmojiEmotions
    EditorTool.TRANSITIONS -> Icons.Filled.SwapHoriz
    EditorTool.EFFECTS -> Icons.Filled.AutoAwesome
    EditorTool.KEYFRAME -> Icons.Filled.Animation
    EditorTool.SETTINGS -> Icons.Filled.Settings
}
