package com.actioncut.feature.editor.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.actioncut.core.designsystem.component.AdjustmentSlider
import com.actioncut.core.designsystem.component.PrimaryButton
import com.actioncut.core.designsystem.component.SecondaryButton
import com.actioncut.core.designsystem.component.SectionHeader
import com.actioncut.core.designsystem.component.SelectableChip
import com.actioncut.core.designsystem.component.rememberHaptic
import com.actioncut.core.model.AnimationGroup
import com.actioncut.core.model.AudioCategory
import com.actioncut.core.model.AudioLibrary
import com.actioncut.core.model.CanvasColors
import com.actioncut.core.model.CanvasSettings
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.ColorAdjustments
import com.actioncut.core.model.CropRect
import com.actioncut.core.model.Emojis
import com.actioncut.core.model.Filter
import com.actioncut.core.model.Filters
import com.actioncut.core.model.FitMode
import com.actioncut.core.model.LibraryTrack
import com.actioncut.core.model.MainAnimation
import com.actioncut.core.model.MainAnimations
import com.actioncut.core.model.SpeedPresets
import com.actioncut.core.model.SpeedRamp
import com.actioncut.core.model.StickerTemplate
import com.actioncut.core.model.Transition
import com.actioncut.core.model.TransitionGroup
import com.actioncut.core.model.TransitionType
import com.actioncut.core.model.VisualEffectType
import com.actioncut.feature.editor.ui.EditorTool
import java.util.Locale
import kotlin.math.roundToInt

/** Bottom parameter panel that swaps content based on the active [EditorTool]. */
@Composable
fun ToolPanel(
    tool: EditorTool,
    selectedClip: Clip?,
    onClose: () -> Unit,
    onSpeed: (Float) -> Unit,
    onSpeedKeepDurationSingle: (Float) -> Unit,
    onSpeedKeepDurationAll: (Float) -> Unit,
    onSpeedRamp: (SpeedRamp) -> Unit,
    onVolume: (Float) -> Unit,
    onFilter: (Filter?) -> Unit,
    onAdjust: (ColorAdjustments) -> Unit,
    onAddText: (String) -> Unit,
    onEditStickerText: (String) -> Unit,
    onAddSticker: (String) -> Unit,
    onAddNewsAlert: (StickerTemplate) -> Unit,
    onAddLowerThird: (StickerTemplate) -> Unit,
    canvas: CanvasSettings,
    onFitMode: (FitMode) -> Unit,
    onBackgroundColor: (Int) -> Unit,
    onCrop: (CropRect) -> Unit,
    onCropPan: (Float, Float) -> Unit,
    onTransition: (Transition?) -> Unit,
    onTransitionAll: (Transition?) -> Unit,
    onTransitionRandom: (TransitionGroup?) -> Unit,
    onToggleEffect: (VisualEffectType) -> Unit,
    onAddLibraryTrack: (LibraryTrack) -> Unit,
    isRecordingVoiceover: Boolean,
    onStartVoiceover: () -> Unit,
    onStopVoiceover: () -> Unit,
    onAddKeyframe: () -> Unit,
    onClearKeyframes: () -> Unit,
    onAnimationSingle: (MainAnimation) -> Unit,
    onAnimationAll: (MainAnimation) -> Unit,
    onAnimationRandom: (AnimationGroup) -> Unit,
    onDurationSingle: (Long) -> Unit,
    onDurationAll: (Long) -> Unit,
    toolbarOrder: List<EditorTool>,
    onSaveToolbarOrder: (List<EditorTool>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .heightIn(min = 120.dp, max = 340.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = tool.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("Done") }
            }

            val requiresSelection = tool !in setOf(
                EditorTool.TEXT,
                EditorTool.STICKER,
                EditorTool.CANVAS,
                EditorTool.MUSIC,
                EditorTool.VOICEOVER,
                EditorTool.NEWS_ALERT,
                EditorTool.LT,
                EditorTool.SETTINGS,
                EditorTool.ANIMATION,
                EditorTool.DURATION,
                EditorTool.TRANSITIONS,
                EditorTool.SPEED,
            )
            if (selectedClip == null && requiresSelection) {
                Text(
                    "Select a clip on the timeline first.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            when (tool) {
                EditorTool.ANIMATION -> MainAnimationPanel(
                    initial = selectedClip?.mainAnimation ?: MainAnimation.NONE,
                    onApplySingle = { onAnimationSingle(it); onClose() },
                    onApplyAll = { onAnimationAll(it); onClose() },
                    onRandom = { onAnimationRandom(it); onClose() },
                )
                EditorTool.DURATION -> DurationPanel(
                    initialMs = selectedClip?.timelineDurationMs ?: 7_000L,
                    onApplySingle = { onDurationSingle(it); onClose() },
                    onApplyAll = { onDurationAll(it); onClose() },
                )
                EditorTool.SPEED -> SpeedPanel(
                    current = selectedClip?.speed ?: 1f,
                    currentRamp = selectedClip?.speedRamp ?: SpeedRamp.NONE,
                    onSpeed = onSpeed,
                    onKeepDurSingle = { onSpeedKeepDurationSingle(it); onClose() },
                    onKeepDurAll = { onSpeedKeepDurationAll(it); onClose() },
                    onSpeedRamp = onSpeedRamp,
                )
                EditorTool.VOLUME -> VolumePanel(selectedClip?.volume ?: 1f, onVolume)
                EditorTool.FILTERS -> FilterPanel(selectedClip?.filter, onFilter)
                EditorTool.ADJUST -> AdjustPanel(
                    key = selectedClip?.id ?: "",
                    current = selectedClip?.adjustments ?: ColorAdjustments.NONE,
                    onAdjust = onAdjust,
                )
                EditorTool.TEXT -> TextPanel(
                    initial = selectedClip?.text?.text ?: "",
                    canUpdateExisting = selectedClip?.type == ClipType.TEXT || selectedClip?.type == ClipType.STICKER,
                    onAddText = { onAddText(it); onClose() },
                    onUpdateText = { onEditStickerText(it); onClose() },
                )
                EditorTool.STICKER -> StickerPanel { onAddSticker(it); onClose() }
                EditorTool.NEWS_ALERT -> NewsAlertPanel { onAddNewsAlert(it); onClose() }
                EditorTool.LT -> LowerThirdPanel { onAddLowerThird(it); onClose() }
                EditorTool.MUSIC -> MusicPanel(onAddLibraryTrack)
                EditorTool.VOICEOVER -> VoiceoverPanel(isRecordingVoiceover, onStartVoiceover, onStopVoiceover)
                EditorTool.CANVAS -> CanvasPanel(canvas, onFitMode, onBackgroundColor)
                EditorTool.CROP -> CropPanel(selectedClip, onCrop, onCropPan)
                EditorTool.TRANSITIONS -> TransitionPanel(
                    current = selectedClip?.transitionToNext,
                    onApplySingle = { onTransition(it); onClose() },
                    onApplyAll = { onTransitionAll(it); onClose() },
                    onRandomAll = { onTransitionRandom(it); onClose() },
                )
                EditorTool.EFFECTS -> EffectsPanel(
                    selectedClip?.effects?.map { it.type }?.toSet() ?: emptySet(),
                    onToggleEffect,
                )
                EditorTool.KEYFRAME -> KeyframePanel(selectedClip, onAddKeyframe, onClearKeyframes)
                EditorTool.SETTINGS -> RearrangeToolbarPanel(
                    initialOrder = toolbarOrder,
                    onSave = onSaveToolbarOrder,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun MainAnimationPanel(
    initial: MainAnimation,
    onApplySingle: (MainAnimation) -> Unit,
    onApplyAll: (MainAnimation) -> Unit,
    onRandom: (AnimationGroup) -> Unit,
) {
    var picked by remember(initial) { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(max = 175.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SectionHeader("Group 1: Panning")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(MainAnimation.NONE) + MainAnimations.panPool) { anim ->
                    SelectableChip(
                        label = anim.displayName,
                        selected = picked == anim,
                        onClick = { picked = anim },
                    )
                }
            }
            SectionHeader("Group 2: Zooming")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MainAnimations.zoomPool) { anim ->
                    SelectableChip(
                        label = anim.displayName,
                        selected = picked == anim,
                        onClick = { picked = anim },
                    )
                }
            }
            SectionHeader("Group 3: Fit Ken Burns (Full Screen)")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MainAnimations.fitPool) { anim ->
                    SelectableChip(
                        label = anim.displayName,
                        selected = picked == anim,
                        onClick = { picked = anim },
                    )
                }
            }
            SectionHeader("Group 4: Combo Curve")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(MainAnimations.comboPool) { anim ->
                    SelectableChip(
                        label = anim.displayName,
                        selected = picked == anim,
                        onClick = { picked = anim },
                    )
                }
            }
            SectionHeader("Group 5: Split Zoom In-Out")
            Row {
                SelectableChip(
                    label = MainAnimation.ZOOM_IN_OUT.displayName,
                    selected = picked == MainAnimation.ZOOM_IN_OUT,
                    onClick = { picked = MainAnimation.ZOOM_IN_OUT },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Rnd Pan", { onRandom(AnimationGroup.PAN) }, Modifier.weight(1f))
            SecondaryButton("Rnd Zoom", { onRandom(AnimationGroup.ZOOM) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Rnd Fit", { onRandom(AnimationGroup.FIT) }, Modifier.weight(1f))
            SecondaryButton("Rnd Combo", { onRandom(AnimationGroup.COMBO) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Apply All", { onApplyAll(picked) }, Modifier.weight(1f))
            PrimaryButton("Apply", { onApplySingle(picked) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DurationPanel(
    initialMs: Long,
    onApplySingle: (Long) -> Unit,
    onApplyAll: (Long) -> Unit,
) {
    var sec by remember(initialMs) { mutableFloatStateOf((initialMs / 1000f).coerceIn(0.5f, 60f)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AdjustmentSlider(
            label = "Duration",
            value = sec,
            onValueChange = { sec = (it * 2f).roundToInt() / 2f },
            valueRange = 0.5f..60f,
            valueFormatter = { String.format(Locale.US, "%.1fs", it) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Apply All", { onApplyAll((sec * 1000f).toLong()) }, Modifier.weight(1f))
            PrimaryButton("Apply", { onApplySingle((sec * 1000f).toLong()) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpeedPanel(
    current: Float,
    currentRamp: SpeedRamp,
    onSpeed: (Float) -> Unit,
    onKeepDurSingle: (Float) -> Unit,
    onKeepDurAll: (Float) -> Unit,
    onSpeedRamp: (SpeedRamp) -> Unit,
) {
    var keepDurSpeed by remember(current) { mutableFloatStateOf(current.coerceIn(0.1f, 4f)) }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(SpeedPresets.values) { speed ->
                SelectableChip(
                    label = "${formatSpeed(speed)}x",
                    selected = current == speed && currentRamp == SpeedRamp.NONE,
                    onClick = { onSpeed(speed) },
                )
            }
        }
        AdjustmentSlider(
            label = "Video Speed (Keep Clip Length)",
            value = keepDurSpeed,
            onValueChange = { keepDurSpeed = (it * 10f).roundToInt() / 10f },
            valueRange = 0.1f..4.0f,
            valueFormatter = { String.format(Locale.US, "%.1fx", it) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Apply All", { onKeepDurAll(keepDurSpeed) }, Modifier.weight(1f))
            PrimaryButton("Apply", { onKeepDurSingle(keepDurSpeed) }, Modifier.weight(1f))
        }
        SectionHeader("Speed ramp")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(SpeedRamp.entries) { ramp ->
                SelectableChip(
                    label = ramp.label,
                    selected = currentRamp == ramp,
                    onClick = { onSpeedRamp(ramp) },
                )
            }
        }
    }
}

@Composable
private fun NewsAlertPanel(onSelect: (StickerTemplate) -> Unit) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NewsPreviewRow("CRiCK NEWS · BREAKING NEWS", Color(0xFFD30000), Color(0xFFA00000)) {
            onSelect(StickerTemplate.NEWS_BREAKING)
        }
        NewsPreviewRow("NEWS CRiCK · FLASH NEWS", Color(0xFFB40000), Color(0xFF500000)) {
            onSelect(StickerTemplate.NEWS_FLASH)
        }
        NewsPreviewRow("NEWS CRiCK · LATEST UPDATE", Color(0xFF001E50), Color(0xFF000A28)) {
            onSelect(StickerTemplate.NEWS_LATEST)
        }
    }
}

@Composable
private fun NewsPreviewRow(title: String, c1: Color, c2: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.horizontalGradient(listOf(c1, c2)))
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 14.sp)
    }
}

@Composable
private fun LowerThirdPanel(onSelect: (StickerTemplate) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF0A1428), Color(0xFF141414))))
                .border(1.dp, Color(0xFFFFD700), RoundedCornerShape(8.dp))
                .clickable { onSelect(StickerTemplate.LOWER_THIRD_GOLD) }
                .padding(14.dp),
        ) {
            Text("LOWER THIRD 1 (Gold / Slide)", color = Color(0xFFFFD700), fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF111111), Color(0xFF333333))))
                .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(20.dp))
                .clickable { onSelect(StickerTemplate.LOWER_THIRD_NEON) }
                .padding(14.dp),
        ) {
            Text("INFO · LOWER THIRD 2 (Neon / Fade)", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RearrangeToolbarPanel(
    initialOrder: List<EditorTool>,
    onSave: (List<EditorTool>) -> Unit,
) {
    var itemsList by remember(initialOrder) { mutableStateOf(initialOrder) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(max = 200.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsList.forEachIndexed { index, tool ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(tool.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = tool.label,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    IconButton(
                        onClick = {
                            if (index > 0) {
                                itemsList = itemsList.toMutableList().apply {
                                    val tmp = removeAt(index)
                                    add(index - 1, tmp)
                                }
                            }
                        },
                        enabled = index > 0,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move Up")
                    }
                    IconButton(
                        onClick = {
                            if (index < itemsList.lastIndex) {
                                itemsList = itemsList.toMutableList().apply {
                                    val tmp = removeAt(index)
                                    add(index + 1, tmp)
                                }
                            }
                        },
                        enabled = index < itemsList.lastIndex,
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move Down")
                    }
                }
            }
        }
        PrimaryButton("Save Order", { onSave(itemsList) }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun KeyframePanel(clip: Clip?, onAddKeyframe: () -> Unit, onClearKeyframes: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "${clip?.keyframes?.size ?: 0} keyframe(s). Move/scale an overlay or PiP, " +
                "scrub the playhead, then add a keyframe to animate position, scale & opacity.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(text = "Add keyframe", onClick = onAddKeyframe)
            SecondaryButton(text = "Clear", onClick = onClearKeyframes)
        }
    }
}

@Composable
private fun VolumePanel(current: Float, onVolume: (Float) -> Unit) {
    AdjustmentSlider(
        label = "Volume",
        value = current,
        onValueChange = onVolume,
        valueRange = 0f..2f,
        valueFormatter = { "${(it * 100).roundToInt()}%" },
    )
}

@Composable
private fun FilterPanel(current: Filter?, onFilter: (Filter?) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(Filters.catalogue) { filter ->
            val isNone = filter.id == Filters.None.id
            val selected = if (isNone) current == null else current?.id == filter.id
            SelectableChip(
                label = filter.name,
                selected = selected,
                onClick = { onFilter(if (isNone) null else filter) },
            )
        }
    }
}

@Composable
private fun AdjustPanel(key: String, current: ColorAdjustments, onAdjust: (ColorAdjustments) -> Unit) {
    var state by remember(key) { mutableStateOf(current) }
    fun push(updated: ColorAdjustments) {
        state = updated
        onAdjust(updated)
    }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AdjustmentSlider("Brightness", state.brightness, { push(state.copy(brightness = it)) }, valueRange = -1f..1f, valueFormatter = ::signed)
        AdjustmentSlider("Contrast", state.contrast, { push(state.copy(contrast = it)) }, valueRange = -1f..1f, valueFormatter = ::signed)
        AdjustmentSlider("Saturation", state.saturation, { push(state.copy(saturation = it)) }, valueRange = -1f..1f, valueFormatter = ::signed)
        AdjustmentSlider("Warmth", state.warmth, { push(state.copy(warmth = it)) }, valueRange = -1f..1f, valueFormatter = ::signed)
    }
}

@Composable
private fun TextPanel(
    initial: String,
    canUpdateExisting: Boolean,
    onAddText: (String) -> Unit,
    onUpdateText: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Caption / Sticker text") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(
                text = "Add text",
                onClick = { if (text.isNotBlank()) onAddText(text) },
            )
            if (canUpdateExisting) {
                SecondaryButton(
                    text = "Update text",
                    onClick = { if (text.isNotBlank()) onUpdateText(text) },
                )
            }
        }
    }
}

@Composable
private fun TransitionPanel(
    current: Transition?,
    onApplySingle: (Transition?) -> Unit,
    onApplyAll: (Transition?) -> Unit,
    onRandomAll: (TransitionGroup?) -> Unit,
) {
    var selectedGroup by remember { mutableStateOf<TransitionGroup?>(null) }
    var pickedType by remember(current) { mutableStateOf(current?.type ?: TransitionType.NONE) }
    val filteredTypes = remember(selectedGroup) {
        if (selectedGroup == null) TransitionType.entries.toList()
        else listOf(TransitionType.NONE) + TransitionType.entries.filter { it.group == selectedGroup }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                SelectableChip(
                    label = "All",
                    selected = selectedGroup == null,
                    onClick = { selectedGroup = null },
                )
            }
            items(TransitionGroup.entries.filter { it != TransitionGroup.NONE }) { grp ->
                SelectableChip(
                    label = grp.displayName,
                    selected = selectedGroup == grp,
                    onClick = { selectedGroup = grp },
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredTypes) { type ->
                SelectableChip(
                    label = type.displayName,
                    selected = pickedType == type,
                    onClick = { pickedType = type },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton("Random All", { onRandomAll(selectedGroup) }, Modifier.weight(1f))
            SecondaryButton(
                "Apply All",
                { onApplyAll(if (pickedType == TransitionType.NONE) null else Transition(pickedType)) },
                Modifier.weight(1f),
            )
            PrimaryButton(
                "Apply",
                { onApplySingle(if (pickedType == TransitionType.NONE) null else Transition(pickedType)) },
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun EffectsPanel(applied: Set<VisualEffectType>, onToggleEffect: (VisualEffectType) -> Unit) {
    val haptic = rememberHaptic()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(VisualEffectType.entries) { type ->
                SelectableChip(
                    label = type.displayName,
                    selected = type in applied,
                    onClick = { haptic.tick(); onToggleEffect(type) },
                )
            }
        }
        Text(
            "Tap to add or remove. Effects render in preview and export.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StickerPanel(onAddSticker: (String) -> Unit) {
    val haptic = rememberHaptic()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(46.dp),
        modifier = Modifier.heightIn(max = 160.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        gridItems(Emojis.popular) { emoji ->
            Text(
                text = emoji,
                fontSize = 30.sp,
                modifier = Modifier
                    .padding(6.dp)
                    .clickable { haptic.tick(); onAddSticker(emoji) },
            )
        }
    }
}

@Composable
private fun MusicPanel(onAddLibraryTrack: (LibraryTrack) -> Unit) {
    val haptic = rememberHaptic()
    var category by remember { mutableStateOf(AudioCategory.MUSIC) }
    val tracks = if (category == AudioCategory.MUSIC) AudioLibrary.music else AudioLibrary.sfx
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectableChip("Music", category == AudioCategory.MUSIC, onClick = { category = AudioCategory.MUSIC })
            SelectableChip("SFX", category == AudioCategory.SFX, onClick = { category = AudioCategory.SFX })
        }
        Column(
            modifier = Modifier
                .heightIn(max = 150.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tracks.forEach { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { haptic.tick(); onAddLibraryTrack(track) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "+ Add",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceoverPanel(
    isRecording: Boolean,
    onStartVoiceover: () -> Unit,
    onStopVoiceover: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRecording) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
            )
            Text(
                if (isRecording) "Recording…" else "Ready to record",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        if (isRecording) {
            PrimaryButton(text = "Stop & add", onClick = onStopVoiceover)
        } else {
            PrimaryButton(text = "Record voiceover", onClick = onStartVoiceover)
        }
    }
}

@Composable
private fun CanvasPanel(
    canvas: CanvasSettings,
    onFitMode: (FitMode) -> Unit,
    onBackgroundColor: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader("Fit")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FitMode.entries) { mode ->
                SelectableChip(mode.displayName, canvas.fitMode == mode, onClick = { onFitMode(mode) })
            }
        }
        SectionHeader("Background")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(CanvasColors.swatches) { argb ->
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(argb))
                        .border(
                            2.dp,
                            if (canvas.backgroundColorArgb == argb) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            CircleShape,
                        )
                        .clickable { onBackgroundColor(argb) },
                )
            }
        }
    }
}

@Composable
private fun CropPanel(
    clip: Clip?,
    onCrop: (CropRect) -> Unit,
    onCropPan: (Float, Float) -> Unit,
) {
    val crop = clip?.crop ?: CropRect()
    val panX = clip?.cropPanX ?: 0.5f
    val panY = clip?.cropPanY ?: 0.5f
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        AdjustmentSlider("Left", crop.left, { onCrop(crop.copy(left = it.coerceIn(0f, crop.right - 0.05f))) }, valueRange = 0f..0.95f, valueFormatter = ::pct)
        AdjustmentSlider("Top", crop.top, { onCrop(crop.copy(top = it.coerceIn(0f, crop.bottom - 0.05f))) }, valueRange = 0f..0.95f, valueFormatter = ::pct)
        AdjustmentSlider("Right", crop.right, { onCrop(crop.copy(right = it.coerceIn(crop.left + 0.05f, 1f))) }, valueRange = 0.05f..1f, valueFormatter = ::pct)
        AdjustmentSlider("Bottom", crop.bottom, { onCrop(crop.copy(bottom = it.coerceIn(crop.top + 0.05f, 1f))) }, valueRange = 0.05f..1f, valueFormatter = ::pct)
        AdjustmentSlider("Pan X", panX, { onCropPan(it, panY) }, valueRange = 0f..1f, valueFormatter = ::pct)
        AdjustmentSlider("Pan Y", panY, { onCropPan(panX, it) }, valueRange = 0f..1f, valueFormatter = ::pct)
        SecondaryButton(
            text = "Reset crop",
            onClick = { onCrop(CropRect()); onCropPan(0.5f, 0.5f) },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun pct(value: Float): String = "${(value * 100).roundToInt()}%"

private fun signed(value: Float): String {
    val pct = (value * 100).roundToInt()
    return if (pct > 0) "+$pct" else "$pct"
}

private fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()
