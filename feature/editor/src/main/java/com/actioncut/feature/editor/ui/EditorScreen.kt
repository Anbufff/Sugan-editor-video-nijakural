package com.actioncut.feature.editor.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actioncut.core.common.time.TimeFormatter
import com.actioncut.core.designsystem.component.PrimaryButton
import com.actioncut.core.designsystem.component.rememberHaptic
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.StickerTemplate
import com.actioncut.core.model.TrackType
import com.actioncut.feature.editor.ui.preview.PreviewPlayer
import com.actioncut.feature.editor.ui.timeline.EditorTimeline
import com.actioncut.feature.editor.ui.tools.EditorToolbar
import com.actioncut.feature.editor.ui.tools.MainTools
import com.actioncut.feature.editor.ui.tools.ToolPanel
import kotlinx.coroutines.delay

private const val PREFS_NAME = "actioncut_toolbar_prefs"
private const val KEY_TOOLBAR_ORDER = "main_toolbar_order"

@Composable
fun EditorScreen(
    onBack: () -> Unit,
    onExport: () -> Unit,
    viewModel: EditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toastMessage.collectAsStateWithLifecycle()
    var showRename by remember { mutableStateOf(false) }
    var showEditTextDialog by remember { mutableStateOf(false) }
    var replaceTargetClipId by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.saveNow()
                Lifecycle.Event.ON_RESUME -> viewModel.onAppResumed()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var toolbarTools by remember {
        mutableStateOf(loadToolbarOrder(context))
    }

    LaunchedEffect(toastMsg) {
        if (toastMsg != null) {
            delay(2_000L)
            viewModel.consumeToast()
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            viewModel.addAudioAtPlayhead(uri.toString())
        }
    }
    val pipPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mime = context.contentResolver.getType(uri).orEmpty()
            viewModel.addPipAtPlayhead(uri.toString(), isImage = mime.startsWith("image/"))
        }
    }
    val replacePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val targetId = replaceTargetClipId
        replaceTargetClipId = null
        if (uri != null && targetId != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val mime = context.contentResolver.getType(uri).orEmpty()
            viewModel.replaceClipMedia(targetId, uri.toString(), isImage = mime.startsWith("image/"))
        }
    }
    val recordPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) viewModel.startVoiceover() }
    val requestVoiceover = {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startVoiceover()
        else recordPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val activeMainClip = remember(uiState.timeline, uiState.playheadMs) {
        uiState.timeline.videoTracks.firstOrNull()?.clipAt(uiState.playheadMs)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding(),
    ) {
        EditorTopBar(
            title = uiState.projectName,
            canUndo = uiState.canUndo,
            canRedo = uiState.canRedo,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onBack = onBack,
            onExport = onExport,
            onTitleClick = { showRename = true },
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            PreviewPlayer(
                player = viewModel.playerController.player,
                aspectRatio = uiState.aspectRatio,
                isPlaying = uiState.isPlaying,
                onTogglePlay = viewModel::togglePlayPause,
                overlays = uiState.timeline.allClips.filter {
                    it.type == ClipType.TEXT || it.type == ClipType.STICKER
                },
                playheadMs = uiState.playheadMs,
                selectedClipId = uiState.selectedClipId,
                onSelectOverlay = viewModel::selectClip,
                onMoveOverlay = viewModel::setOverlayPosition,
                pipPlayer = viewModel.playerController.pipPlayer,
                pipClips = uiState.timeline.tracks
                    .filter { it.type == TrackType.OVERLAY }
                    .flatMap { it.clips }
                    .filter { it.type == ClipType.VIDEO || it.type == ClipType.IMAGE },
                onScaleOverlay = viewModel::setOverlayScale,
                fitMode = uiState.canvas.fitMode,
                backgroundColorArgb = uiState.canvas.backgroundColorArgb,
                activeMainClip = activeMainClip,
                onPanZoomMainClip = viewModel::setMainClipPanZoom,
                onRotateOverlay = viewModel::setOverlayRotation,
                onFlipArrow = { clipId ->
                    viewModel.selectClip(clipId)
                    viewModel.flipSelectedHorizontal()
                },
                onDoubleTapOverlay = { clipId ->
                    viewModel.selectClip(clipId)
                    showEditTextDialog = true
                },
                modifier = Modifier.fillMaxSize(),
            )

            toastMsg?.let { msg ->
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC061A0E))
                        .border(1.dp, Color(0xFF00E676), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = msg,
                        color = Color(0xFF00E676),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        PlaybackBar(
            playheadMs = uiState.playheadMs,
            durationMs = uiState.durationMs,
            isPlaying = uiState.isPlaying,
            onTogglePlay = viewModel::togglePlayPause,
        )

        EditorTimeline(
            timeline = uiState.timeline,
            playheadMs = uiState.playheadMs,
            durationMs = uiState.durationMs,
            pxPerSecond = uiState.pxPerSecond,
            isPlaying = uiState.isPlaying,
            selectedClipId = uiState.selectedClipId,
            onScrub = viewModel::seekTo,
            onSelectClip = viewModel::selectClip,
            onTrimStart = viewModel::trimStart,
            onTrimEnd = viewModel::trimEnd,
            onMoveClip = viewModel::moveClip,
            onMoveCommit = viewModel::commitArrangement,
            onLoadWaveform = viewModel::loadWaveform,
            onTransitionClick = { clipId ->
                viewModel.selectClip(clipId)
                viewModel.setActiveTool(EditorTool.TRANSITIONS)
            },
            onLongPressClip = { clipId ->
                replaceTargetClipId = clipId
                replacePicker.launch(arrayOf("image/*", "video/*"))
            },
            onSelectKeyframe = { kfTimeMs -> viewModel.seekTo(kfTimeMs) },
            onPausePlayback = viewModel::pausePlayback,
            onZoomChange = viewModel::setZoom,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
        )

        Box(modifier = Modifier.navigationBarsPadding()) {
            val tool = uiState.activeTool
            if (tool != null && tool.opensPanel) {
                ToolPanel(
                    tool = tool,
                    selectedClip = uiState.selectedClip,
                    onClose = { viewModel.setActiveTool(null) },
                    onSpeed = viewModel::setSpeed,
                    onSpeedKeepDurationSingle = viewModel::setSpeedKeepDurationSingle,
                    onSpeedKeepDurationAll = viewModel::setSpeedKeepDurationAll,
                    onSpeedRamp = viewModel::setSpeedRamp,
                    onVolume = viewModel::setVolume,
                    onFilter = viewModel::setFilter,
                    onAdjust = viewModel::setAdjustments,
                    onAddText = viewModel::addTextAtPlayhead,
                    onEditStickerText = viewModel::editSelectedStickerText,
                    onAddSticker = viewModel::addStickerAtPlayhead,
                    onAddNewsAlert = viewModel::addNewsAlertSticker,
                    onAddLowerThird = viewModel::addLowerThirdSticker,
                    canvas = uiState.canvas,
                    onFitMode = viewModel::setFitMode,
                    onBackgroundColor = viewModel::setBackgroundColor,
                    onCrop = { rect -> uiState.selectedClipId?.let { viewModel.setCrop(it, rect) } },
                    onCropPan = { px, py -> uiState.selectedClipId?.let { viewModel.setCropPan(it, px, py) } },
                    onTransition = viewModel::setTransition,
                    onTransitionAll = viewModel::setTransitionAll,
                    onTransitionRandom = viewModel::applyTransitionsRandom,
                    onToggleEffect = viewModel::toggleEffect,
                    onAddLibraryTrack = viewModel::addLibraryTrackAtPlayhead,
                    isRecordingVoiceover = uiState.isRecordingVoiceover,
                    onStartVoiceover = requestVoiceover,
                    onStopVoiceover = viewModel::stopVoiceover,
                    onAddKeyframe = viewModel::addKeyframeAtPlayhead,
                    onClearKeyframes = viewModel::clearKeyframes,
                    onAnimationSingle = viewModel::setMainAnimationSingle,
                    onAnimationAll = viewModel::setMainAnimationAll,
                    onAnimationRandom = viewModel::applyMainAnimationRandom,
                    onDurationSingle = viewModel::setDurationSingle,
                    onDurationAll = viewModel::setDurationAll,
                    toolbarOrder = toolbarTools,
                    onSaveToolbarOrder = { newOrder ->
                        toolbarTools = newOrder
                        saveToolbarOrder(context, newOrder)
                        viewModel.setActiveTool(null)
                    },
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val sel = uiState.selectedClip
                    if (sel != null) {
                        val isOverlayTrack = uiState.timeline.tracks.any { t ->
                            t.type == TrackType.OVERLAY && t.clips.any { it.id == sel.id }
                        }
                        SelectedClipBar(
                            onSplit = viewModel::splitAtPlayhead,
                            onDuplicate = viewModel::duplicateSelected,
                            onDelete = viewModel::deleteSelected,
                            onDeselect = { viewModel.selectClip(null) },
                            showReplace = sel.type == ClipType.VIDEO || sel.type == ClipType.IMAGE,
                            onReplace = {
                                replaceTargetClipId = sel.id
                                replacePicker.launch(arrayOf("image/*", "video/*"))
                            },
                            onCapture = viewModel::captureFrameAtPlayhead,
                        )
                        val showExtra = sel.type == ClipType.AUDIO ||
                            isOverlayTrack ||
                            sel.type == ClipType.TEXT ||
                            sel.type == ClipType.STICKER
                        if (showExtra) {
                            ContextualExtraStrip(
                                clip = sel,
                                isOverlayTrack = isOverlayTrack,
                                onToggleLock = viewModel::toggleAudioLockSelected,
                                onFlipArrow = viewModel::flipSelectedHorizontal,
                                onToggleKeyframe = viewModel::toggleKeyframeAtPlayhead,
                                onClipEnd = viewModel::extendOverlayToClipEnd,
                                onVidEnd = viewModel::extendOverlayToVideoEnd,
                                onEditText = { showEditTextDialog = true },
                                onCrop = { viewModel.setActiveTool(EditorTool.CROP) },
                                onTrimR = viewModel::trimRightToPlayhead,
                                onCutout = viewModel::toggleCutoutSelected,
                            )
                        }
                    }
                    EditorToolbar(
                        activeTool = uiState.activeTool,
                        onToolSelected = { selectedTool ->
                            when (selectedTool) {
                                EditorTool.AUDIO -> audioPicker.launch(arrayOf("audio/*"))
                                EditorTool.PIP -> pipPicker.launch(arrayOf("image/*", "video/*"))
                                else -> viewModel.setActiveTool(selectedTool)
                            }
                        },
                        tools = toolbarTools,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }

    if (showRename) {
        RenameDialog(
            currentName = uiState.projectName,
            onDismiss = { showRename = false },
            onConfirm = viewModel::renameProject,
        )
    }

    if (showEditTextDialog) {
        val currentText = uiState.selectedClip?.text?.text.orEmpty()
        EditTextDialog(
            currentText = currentText,
            onDismiss = { showEditTextDialog = false },
            onConfirm = { newText -> viewModel.editSelectedStickerText(newText) },
        )
    }
}

@Composable
private fun ContextualExtraStrip(
    clip: Clip,
    isOverlayTrack: Boolean,
    onToggleLock: () -> Unit,
    onFlipArrow: () -> Unit,
    onToggleKeyframe: () -> Unit,
    onClipEnd: () -> Unit,
    onVidEnd: () -> Unit,
    onEditText: () -> Unit,
    onCrop: () -> Unit,
    onTrimR: () -> Unit,
    onCutout: () -> Unit,
) {
    val isOverlayOrText = isOverlayTrack || clip.type == ClipType.TEXT || clip.type == ClipType.STICKER
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (clip.type == ClipType.AUDIO) {
            FilterChip(
                selected = clip.isLocked,
                onClick = onToggleLock,
                label = { Text(if (clip.isLocked) "Unlock" else "Lock") },
                leadingIcon = {
                    Icon(
                        imageVector = if (clip.isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        if (clip.stickerTemplate == StickerTemplate.ARROW) {
            FilterChip(
                selected = clip.flipHorizontal,
                onClick = onFlipArrow,
                label = { Text("Flip") },
                leadingIcon = { Icon(Icons.Filled.Flip, null, Modifier.size(16.dp)) },
            )
            FilterChip(
                selected = false,
                onClick = onToggleKeyframe,
                label = { Text("KF +/-") },
                leadingIcon = { Icon(Icons.Filled.Tune, null, Modifier.size(16.dp)) },
            )
        }
        if (isOverlayOrText) {
            FilterChip(
                selected = false,
                onClick = onClipEnd,
                label = { Text("Clip End") },
                leadingIcon = { Icon(Icons.Filled.SkipNext, null, Modifier.size(16.dp)) },
            )
            FilterChip(
                selected = false,
                onClick = onVidEnd,
                label = { Text("Vid End") },
                leadingIcon = { Icon(Icons.Filled.FastForward, null, Modifier.size(16.dp)) },
            )
            if (clip.type == ClipType.STICKER || clip.type == ClipType.TEXT) {
                FilterChip(
                    selected = false,
                    onClick = onEditText,
                    label = { Text("Edit Text") },
                    leadingIcon = { Icon(Icons.Filled.Edit, null, Modifier.size(16.dp)) },
                )
            } else {
                FilterChip(
                    selected = clip.crop != null && !clip.crop!!.isFull,
                    onClick = onCrop,
                    label = { Text("Crop") },
                    leadingIcon = { Icon(Icons.Filled.Crop, null, Modifier.size(16.dp)) },
                )
                FilterChip(
                    selected = clip.cutoutEnabled,
                    onClick = onCutout,
                    label = { Text("Cutout") },
                    leadingIcon = { Icon(Icons.Filled.PersonOutline, null, Modifier.size(16.dp)) },
                )
            }
            FilterChip(
                selected = false,
                onClick = onTrimR,
                label = { Text("Trim R") },
                leadingIcon = { Icon(Icons.Filled.ContentCut, null, Modifier.size(16.dp)) },
            )
        }
    }
}

private fun loadToolbarOrder(context: Context): List<EditorTool> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_TOOLBAR_ORDER, null) ?: return MainTools
    val saved = raw.split(",").mapNotNull { name ->
        runCatching { EditorTool.valueOf(name) }.getOrNull()
    }
    val missing = MainTools.filterNot { it in saved }
    return saved + missing
}

private fun saveToolbarOrder(context: Context, order: List<EditorTool>) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_TOOLBAR_ORDER, order.joinToString(",") { it.name })
        .apply()
}

@Composable
private fun EditorTopBar(
    title: String,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onBack: () -> Unit,
    onExport: () -> Unit,
    onTitleClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onTitleClick() }
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title.ifBlank { "Untitled" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Rename project",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
        }
        PrimaryButton(text = "Export", onClick = onExport, leadingIcon = Icons.Filled.Add)
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(currentName) { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                label = { Text("Project name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EditTextDialog(
    currentText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var draft by remember(currentText) { mutableStateOf(currentText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Sticker Text") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = false,
                maxLines = 3,
                label = { Text("Text") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft); onDismiss() }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun PlaybackBar(
    playheadMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
) {
    val haptic = rememberHaptic()
    val colourfulGradient = Brush.linearGradient(
        listOf(
            Color(0xFFFF2E4D),
            Color(0xFFFF8C42),
            Color(0xFF00E5FF),
        ),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = "${TimeFormatter.precise(playheadMs)} / ${TimeFormatter.clock(durationMs)}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF00E5FF),
        )
        Box(
            modifier = Modifier
                .width(72.dp)
                .height(44.dp)
                .shadow(10.dp, RoundedCornerShape(22.dp), spotColor = Color(0xFFFF2E4D))
                .clip(RoundedCornerShape(22.dp))
                .background(colourfulGradient)
                .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(22.dp))
                .clickable { haptic.click(); onTogglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = Color.White,
                modifier = Modifier.size(30.dp),
            )
        }
    }
}
