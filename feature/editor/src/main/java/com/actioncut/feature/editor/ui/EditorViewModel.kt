package com.actioncut.feature.editor.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actioncut.core.common.id.Ids
import com.actioncut.core.domain.editor.ClipFactory
import com.actioncut.core.domain.editor.TimelineEditor
import com.actioncut.core.domain.usecase.GetProjectUseCase
import com.actioncut.core.domain.usecase.ResolveLibraryAudioUseCase
import com.actioncut.core.domain.usecase.ResolveMediaUseCase
import com.actioncut.core.domain.usecase.SaveProjectUseCase
import com.actioncut.core.media.audio.VoiceRecorder
import com.actioncut.core.media.player.PlayerController
import com.actioncut.core.media.waveform.WaveformExtractor
import com.actioncut.core.model.AnimationGroup
import com.actioncut.core.model.AspectRatio
import com.actioncut.core.model.CanvasSettings
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.ColorAdjustments
import com.actioncut.core.model.CropRect
import com.actioncut.core.model.Filter
import com.actioncut.core.model.FitMode
import com.actioncut.core.model.Keyframe
import com.actioncut.core.model.LibraryTrack
import com.actioncut.core.model.MainAnimation
import com.actioncut.core.model.MainAnimations
import com.actioncut.core.model.MediaType
import com.actioncut.core.model.Project
import com.actioncut.core.model.SpeedRamp
import com.actioncut.core.model.StickerTemplate
import com.actioncut.core.model.TextProperties
import com.actioncut.core.model.Timeline
import com.actioncut.core.model.TrackType
import com.actioncut.core.model.Transform
import com.actioncut.core.model.Transition
import com.actioncut.core.model.TransitionGroup
import com.actioncut.core.model.TransitionType
import com.actioncut.core.model.VisualEffect
import com.actioncut.core.model.VisualEffectType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.math.abs

@HiltViewModel
class EditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val getProject: GetProjectUseCase,
    private val saveProject: SaveProjectUseCase,
    private val resolveMedia: ResolveMediaUseCase,
    private val resolveLibraryAudio: ResolveLibraryAudioUseCase,
    private val waveformExtractor: WaveformExtractor,
    private val voiceRecorder: VoiceRecorder,
    val playerController: PlayerController,
) : ViewModel() {

    private val projectId: String = savedStateHandle[ARG_PROJECT_ID] ?: ""

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var loadedProject: Project? = null
    private val undoStack = ArrayDeque<Timeline>()
    private val redoStack = ArrayDeque<Timeline>()
    private var saveJob: Job? = null

    init {
        observePlayback()
        loadProject()
    }

    private fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    fun consumeToast() {
        _toastMessage.value = null
    }

    private fun loadProject() {
        viewModelScope.launch {
            val project = getProject(projectId)
            if (project == null) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            // Ensure 16:9 canvas matches preview and export consistently.
            val normalized = if (project.aspectRatio != AspectRatio.RATIO_16_9) {
                project.copy(aspectRatio = AspectRatio.RATIO_16_9)
            } else {
                project
            }
            loadedProject = normalized
            _uiState.update {
                it.copy(
                    isLoading = false,
                    projectName = normalized.name,
                    timeline = normalized.timeline,
                    aspectRatio = AspectRatio.RATIO_16_9,
                    canvas = normalized.canvas,
                    selectedClipId = normalized.timeline.allClips.firstOrNull()?.id,
                )
            }
            playerController.setTimeline(normalized.timeline)
        }
    }

    private fun observePlayback() {
        viewModelScope.launch {
            playerController.state.collect { playback ->
                _uiState.update { state ->
                    val following = if (playback.isPlaying) {
                        playback.positionMs.coerceIn(0, state.durationMs)
                    } else {
                        state.playheadMs
                    }
                    state.copy(isPlaying = playback.isPlaying, playheadMs = following)
                }
            }
        }
        viewModelScope.launch {
            playerController.positionFlow().collect { positionMs ->
                if (_uiState.value.isPlaying) {
                    _uiState.update { it.copy(playheadMs = positionMs.coerceIn(0, it.durationMs)) }
                }
            }
        }
    }

    // ------------------------------------------------------------------ playback / lifecycle

    fun togglePlayPause() = playerController.togglePlayPause()

    /** Pauses playback immediately (e.g. when user touches the main timeline). */
    fun pausePlayback() {
        if (_uiState.value.isPlaying) {
            playerController.pause()
        }
    }

    /** Restores preview state when returning from Home button or screen-off. */
    fun onAppResumed() {
        playerController.restoreAfterResume(_uiState.value.timeline, _uiState.value.playheadMs)
    }

    fun seekTo(positionMs: Long) {
        val clamped = positionMs.coerceIn(0, _uiState.value.durationMs)
        playerController.seekTo(clamped)
        _uiState.update { it.copy(playheadMs = clamped) }
    }

    // ------------------------------------------------------------------ selection / tools

    fun selectClip(clipId: String?) = _uiState.update { it.copy(selectedClipId = clipId) }

    fun renameProject(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(projectName = trimmed) }
        loadedProject = loadedProject?.copy(name = trimmed)
        scheduleSave()
    }

    suspend fun loadWaveform(uri: String): FloatArray = waveformExtractor.amplitudes(uri)

    fun setActiveTool(tool: EditorTool?) {
        when (tool) {
            EditorTool.SPLIT -> { splitAtPlayhead(); clearTool() }
            EditorTool.DELETE -> { deleteSelected(); clearTool() }
            EditorTool.TRIM_R -> { trimRightToPlayhead(); clearTool() }
            EditorTool.REVERSE -> { toggleReverse(); clearTool() }
            EditorTool.ROTATE -> { rotateSelected(); clearTool() }
            EditorTool.MUTE -> { toggleMuteSelected(); clearTool() }
            EditorTool.EXTRACT_AUDIO -> { detachAudioFromSelected(); clearTool() }
            EditorTool.SUBSCRIBE -> { addSubscribeSticker(); clearTool() }
            EditorTool.ARROW -> { addArrowSticker(); clearTool() }
            EditorTool.CAPTURE -> { captureFrameAtPlayhead(); clearTool() }
            EditorTool.DEL_VIDEOS -> { deleteAllVideosKeepImages(); clearTool() }
            EditorTool.AUDIO, EditorTool.PIP -> clearTool()
            else -> _uiState.update { it.copy(activeTool = tool) }
        }
    }

    private fun clearTool() = _uiState.update { it.copy(activeTool = null) }

    fun setZoom(pxPerSecond: Float) = _uiState.update {
        it.copy(
            pxPerSecond = pxPerSecond.coerceIn(
                EditorUiState.MIN_PX_PER_SECOND,
                EditorUiState.MAX_PX_PER_SECOND,
            ),
        )
    }

    // ------------------------------------------------------------------ structural edits

    fun splitAtPlayhead() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: clipAtPlayhead()?.id ?: return
        mutate(structural = true) { TimelineEditor.splitClip(it, clipId, state.playheadMs) }
    }

    fun deleteSelected() {
        val clipId = _uiState.value.selectedClipId ?: return
        mutate(structural = true) { TimelineEditor.removeClip(it, clipId, ripple = true) }
        _uiState.update { it.copy(selectedClipId = null) }
    }

    fun duplicateSelected() = withSelected { id ->
        val (track, clip) = TimelineEditor.findClip(_uiState.value.timeline, id) ?: return@withSelected
        val copy = clip.copy(
            id = UUID.randomUUID().toString(),
            timelineStartMs = clip.timelineEndMs,
            timelineEndMs = clip.timelineEndMs + clip.timelineDurationMs,
        )
        mutate(structural = true) { TimelineEditor.insertClip(it, track.id, copy) }
        _uiState.update { it.copy(selectedClipId = copy.id) }
    }

    fun trimStart(clipId: String, newStartMs: Long) =
        mutate(structural = true) { TimelineEditor.trimClipStart(it, clipId, newStartMs) }

    fun trimEnd(clipId: String, newEndMs: Long) =
        mutate(structural = true) { TimelineEditor.trimClipEnd(it, clipId, newEndMs) }

    fun trimRightToPlayhead() {
        val state = _uiState.value
        val clipId = state.selectedClipId ?: clipAtPlayhead()?.id ?: return
        val clip = currentClip(clipId) ?: return
        if (state.playheadMs <= clip.timelineStartMs || state.playheadMs >= clip.timelineEndMs) {
            showToast("Move playhead inside clip!")
            return
        }
        mutate(structural = true) { TimelineEditor.trimEndTo(it, clipId, state.playheadMs) }
        showToast("Trimmed Right!")
    }

    fun moveClip(clipId: String, newStartMs: Long) {
        val track = TimelineEditor.findClip(_uiState.value.timeline, clipId)?.first ?: return
        mutate(structural = false) {
            TimelineEditor.moveClip(it, clipId, track.id, newStartMs.coerceAtLeast(0))
        }
    }

    fun commitArrangement() {
        playerController.setTimeline(_uiState.value.timeline)
        playerController.seekTo(_uiState.value.playheadMs)
    }

    fun detachAudioFromSelected() = withSelected { id ->
        mutate(structural = true) { TimelineEditor.detachAudio(it, id) }
    }

    // ------------------------------------------------------------------ HTML Editor Features

    fun captureFrameAtPlayhead() {
        val playhead = _uiState.value.playheadMs
        val ac = _uiState.value.timeline.videoTracks.firstOrNull()?.clipAt(playhead)
        if (ac == null || ac.type != ClipType.VIDEO) {
            showToast("Playhead-ஐ ஒரு VIDEO Clip-க்குள் வையுங்கள்!")
            return
        }
        viewModelScope.launch {
            val capturedUri = playerController.captureFrame(ac, playhead)
            if (capturedUri == null) {
                showToast("Video Frame Ready இல்லை, மீண்டும் முயற்சிக்கவும்!")
                return@launch
            }
            val relMs = (playhead - ac.timelineStartMs).coerceAtLeast(0L)
            mutate(structural = true) { timeline ->
                val mainTrack = timeline.videoTracks.firstOrNull() ?: return@mutate timeline
                val marked = TimelineEditor.addCaptureMark(timeline, ac.id, relMs)
                val photo = Clip(
                    id = Ids.clip(),
                    type = ClipType.IMAGE,
                    mediaUri = capturedUri,
                    timelineStartMs = 0L,
                    timelineEndMs = 7_000L,
                    mainAnimation = MainAnimation.NONE,
                )
                TimelineEditor.appendClip(marked, mainTrack.id, photo)
            }
            showToast("Frame Capture செய்யப்பட்டது!")
        }
    }

    fun deleteAllVideosKeepImages() {
        val mainTrack = _uiState.value.timeline.videoTracks.firstOrNull()
        if (mainTrack == null || mainTrack.clips.none { it.type == ClipType.VIDEO }) {
            showToast("Timeline-ல் Video Clip இல்லை!")
            return
        }
        mutate(structural = true) { TimelineEditor.deleteAllVideosKeepImages(it) }
        _uiState.update { it.copy(selectedClipId = it.timeline.allClips.firstOrNull()?.id) }
        seekTo(0L)
        showToast("அனைத்து Videoகளும் நீக்கப்பட்டன! Screenshots மட்டும் உள்ளது")
    }

    fun setDurationSingle(durationMs: Long) {
        val id = _uiState.value.selectedClipId ?: clipAtPlayhead()?.id ?: return
        mutate(structural = true) { TimelineEditor.setDuration(it, id, durationMs, ripple = true) }
        showToast("Duration Applied!")
    }

    fun setDurationAll(durationMs: Long) {
        mutate(structural = true) { TimelineEditor.setDurationAll(it, durationMs) }
        showToast("Duration Applied to All!")
    }

    fun setSpeed(speed: Float) = withSelected { id ->
        mutate(structural = true) { TimelineEditor.setSpeed(it, id, speed) }
    }

    fun setSpeedKeepDurationSingle(speed: Float) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.setSpeedKeepDuration(it, id, speed) }
        showToast("Speed ${"%.1f".format(speed)}x Applied!")
    }

    fun setSpeedKeepDurationAll(speed: Float) {
        mutate(structural = false) { TimelineEditor.setSpeedKeepDurationAll(it, speed) }
        showToast("Speed ${"%.1f".format(speed)}x Applied to All!")
    }

    fun setMainAnimationSingle(anim: MainAnimation) {
        val id = _uiState.value.selectedClipId ?: clipAtPlayhead()?.id ?: return
        mutate(structural = false) { TimelineEditor.setMainAnimation(it, id, anim) }
        showToast("Animation Applied!")
    }

    fun setMainAnimationAll(anim: MainAnimation) {
        mutate(structural = false) { TimelineEditor.setMainAnimationAll(it, anim) }
        showToast("Animation Applied to All!")
    }

    fun applyMainAnimationRandom(group: AnimationGroup) {
        val pool = when (group) {
            AnimationGroup.PAN -> MainAnimations.panPool
            AnimationGroup.ZOOM -> MainAnimations.zoomPool
            AnimationGroup.FIT -> MainAnimations.fitPool
            AnimationGroup.COMBO -> MainAnimations.comboPool
            else -> MainAnimations.panPool
        }
        mutate(structural = false) { TimelineEditor.setMainAnimationRandom(it, pool) }
        showToast("Random ${group.displayName} Applied!")
    }

    fun setMainClipPanZoom(clipId: String, offsetX: Float, offsetY: Float, scale: Float) {
        val clip = currentClip(clipId) ?: return
        mutate(structural = false) {
            TimelineEditor.setTransform(
                it,
                clipId,
                clip.transform.copy(
                    offsetX = offsetX.coerceIn(-1f, 1f),
                    offsetY = offsetY.coerceIn(-1f, 1f),
                    scale = scale.coerceIn(0.5f, 4.0f),
                ),
            )
        }
    }

    fun setTransition(transition: Transition?) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.setTransition(it, id, transition) }
    }

    fun setTransitionAll(transition: Transition?) {
        mutate(structural = false) { TimelineEditor.setTransitionAll(it, transition) }
        showToast("Transition Applied to All!")
    }

    fun applyTransitionsRandom(group: TransitionGroup?) {
        val pool = if (group == null || group == TransitionGroup.NONE) {
            TransitionType.entries.filter { it != TransitionType.NONE && it != TransitionType.SEAMLESS }
        } else {
            TransitionType.entries.filter { it.group == group }
        }
        mutate(structural = false) { TimelineEditor.setTransitionsRandom(it, pool) }
        showToast("Transitions Randomized!")
    }

    fun toggleReverse() = withSelected { id ->
        val current = currentClip(id)?.isReversed ?: false
        mutate(structural = true) { TimelineEditor.setReversed(it, id, !current) }
    }

    fun addTextAtPlayhead(text: String) {
        val state = _uiState.value
        val track = state.timeline.tracks.firstOrNull { it.type == TrackType.TEXT }
        val clip = ClipFactory.text(text, state.playheadMs)
        mutate(structural = true) { timeline ->
            if (track != null) {
                TimelineEditor.insertClip(timeline, track.id, clip)
            } else {
                val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.TEXT)
                TimelineEditor.insertClip(withTrack, trackId, clip)
            }
        }
        _uiState.update { it.copy(selectedClipId = clip.id) }
    }

    fun addNewsAlertSticker(template: StickerTemplate) {
        val defaultText = when (template) {
            StickerTemplate.NEWS_BREAKING -> "BREAKING NEWS"
            StickerTemplate.NEWS_FLASH -> "FLASH NEWS"
            else -> "LATEST UPDATE"
        }
        val offsetY = if (template == StickerTemplate.NEWS_BREAKING) 0.35f else 0f
        val offsetX = when (template) {
            StickerTemplate.NEWS_FLASH -> 0.25f
            StickerTemplate.NEWS_LATEST -> -0.25f
            else -> 0f
        }
        addTemplateSticker(template, defaultText, durationMs = 5_000L, offsetX = offsetX, offsetY = offsetY)
        showToast("News Alert Added!")
    }

    fun addSubscribeSticker() {
        addTemplateSticker(StickerTemplate.YOUTUBE_SUBSCRIBE, "NEWS CRiCK", durationMs = 5_000L)
        showToast("Subscribe Sticker Added!")
    }

    fun addLowerThirdSticker(template: StickerTemplate) {
        val label = if (template == StickerTemplate.LOWER_THIRD_GOLD) "LOWER THIRD 1" else "LOWER THIRD 2"
        addTemplateSticker(template, label, durationMs = 5_000L, offsetY = 0.65f)
        showToast("Lower Third Added!")
    }

    fun addArrowSticker() {
        val start = _uiState.value.playheadMs
        val clip = Clip(
            id = Ids.clip(),
            type = ClipType.STICKER,
            timelineStartMs = start,
            timelineEndMs = start + 5_000L,
            stickerTemplate = StickerTemplate.ARROW,
            text = TextProperties(text = ""),
            transform = Transform(scale = 0.6f),
            keyframes = listOf(Keyframe(timeMs = 0L, scale = 0.6f)),
        )
        insertOnOverlayTrack(clip)
        showToast("Arrow Added!")
    }

    private fun addTemplateSticker(
        template: StickerTemplate,
        text: String,
        durationMs: Long,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ) {
        val start = _uiState.value.playheadMs
        val clip = Clip(
            id = Ids.clip(),
            type = ClipType.STICKER,
            timelineStartMs = start,
            timelineEndMs = start + durationMs,
            stickerTemplate = template,
            text = TextProperties(text = text),
            transform = Transform(offsetX = offsetX, offsetY = offsetY),
        )
        insertOnOverlayTrack(clip)
    }

    private fun insertOnOverlayTrack(clip: Clip) {
        val track = _uiState.value.timeline.tracks.firstOrNull { it.type == TrackType.OVERLAY }
        mutate(structural = false) { timeline ->
            if (track != null) {
                TimelineEditor.addClip(timeline, track.id, clip)
            } else {
                val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.OVERLAY)
                TimelineEditor.addClip(withTrack, trackId, clip)
            }
        }
        _uiState.update { it.copy(selectedClipId = clip.id) }
    }

    fun editSelectedStickerText(newText: String) = withSelected { id ->
        val trimmed = newText.trim()
        if (trimmed.isEmpty()) return@withSelected
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c ->
                val cur = c.text ?: TextProperties(text = trimmed)
                c.copy(text = cur.copy(text = trimmed))
            }
        }
        showToast("Text Updated!")
    }

    fun flipSelectedHorizontal() = withSelected { id ->
        val cur = currentClip(id)?.flipHorizontal ?: false
        mutate(structural = false) { TimelineEditor.setFlipHorizontal(it, id, !cur) }
        showToast("Flipped!")
    }

    fun toggleCutoutSelected() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val next = !clip.cutoutEnabled
        mutate(structural = false) { TimelineEditor.setCutout(it, id, next) }
        showToast(if (next) "AI Cutout: ON" else "Cutout: OFF")
    }

    fun extendOverlayToClipEnd() = withSelected { id ->
        mutate(structural = false) { TimelineEditor.extendOverlayToClipEnd(it, id) }
        showToast("Set to Clip End")
    }

    fun extendOverlayToVideoEnd() = withSelected { id ->
        mutate(structural = false) { TimelineEditor.extendOverlayToVideoEnd(it, id) }
        showToast("Set to Video End")
    }

    fun toggleAudioLockSelected() = withSelected { id ->
        val cur = currentClip(id)?.isLocked ?: false
        mutate(structural = false) { TimelineEditor.setAudioLocked(it, id, !cur) }
        showToast(if (!cur) "Audio Locked!" else "Audio Unlocked!")
    }

    fun replaceClipMedia(clipId: String, uri: String, isImage: Boolean = false) {
        viewModelScope.launch {
            if (isImage) {
                mutate(structural = true) {
                    TimelineEditor.replaceClipMedia(it, clipId, uri, ClipType.IMAGE, 7_000L)
                }
            } else {
                val media = resolveMedia(uri)
                val dur = media?.durationMs?.coerceAtLeast(TimelineEditor.MIN_CLIP_DURATION_MS) ?: 5_000L
                val type = if (media?.type == MediaType.VIDEO) ClipType.VIDEO else ClipType.IMAGE
                mutate(structural = true) {
                    TimelineEditor.replaceClipMedia(it, clipId, uri, type, dur)
                }
            }
            showToast("Media Replaced!")
        }
    }

    fun addAudioAtPlayhead(uri: String) {
        viewModelScope.launch {
            val media = resolveMedia(uri) ?: return@launch
            val audioMedia = media.copy(type = MediaType.AUDIO)
            val clip = ClipFactory.fromMedia(audioMedia, _uiState.value.playheadMs)
            val track = _uiState.value.timeline.tracks.firstOrNull { it.type == TrackType.AUDIO }
            mutate(structural = true) { timeline ->
                if (track != null) {
                    TimelineEditor.insertClip(timeline, track.id, clip)
                } else {
                    val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.AUDIO)
                    TimelineEditor.insertClip(withTrack, trackId, clip)
                }
            }
            _uiState.update { it.copy(selectedClipId = clip.id) }
        }
    }

    fun toggleMuteSelected() = withSelected { id ->
        val muted = (currentClip(id)?.volume ?: 1f) == 0f
        mutate(structural = false) { TimelineEditor.setVolume(it, id, if (muted) 1f else 0f) }
    }

    fun addLibraryTrackAtPlayhead(track: LibraryTrack) {
        viewModelScope.launch {
            val uri = resolveLibraryAudio(track.rawResName) ?: return@launch
            addAudioAtPlayhead(uri)
        }
    }

    fun startVoiceover() {
        if (voiceRecorder.start()) {
            _uiState.update { it.copy(isRecordingVoiceover = true) }
        }
    }

    fun stopVoiceover() {
        val uri = voiceRecorder.stop()
        _uiState.update { it.copy(isRecordingVoiceover = false) }
        if (uri != null) addAudioAtPlayhead(uri)
    }

    fun addStickerAtPlayhead(emoji: String) {
        val clip = ClipFactory.emojiSticker(emoji, _uiState.value.playheadMs)
        insertOnOverlayTrack(clip)
    }

    fun setOverlayPosition(clipId: String, offsetX: Float, offsetY: Float) {
        val clip = currentClip(clipId) ?: return
        mutate(structural = false) {
            TimelineEditor.setTransform(it, clipId, clip.transform.copy(offsetX = offsetX, offsetY = offsetY))
        }
    }

    fun setOverlayScale(clipId: String, scale: Float) {
        val clip = currentClip(clipId) ?: return
        mutate(structural = false) {
            TimelineEditor.setTransform(it, clipId, clip.transform.copy(scale = scale.coerceIn(0.1f, 2f)))
        }
    }

    fun setOverlayRotation(clipId: String, degrees: Float) {
        val clip = currentClip(clipId) ?: return
        mutate(structural = false) {
            TimelineEditor.setTransform(it, clipId, clip.transform.copy(rotationDegrees = degrees))
        }
    }

    // ------------------------------------------------------------------ canvas / crop

    fun setFitMode(mode: FitMode) = updateCanvas { it.copy(fitMode = mode) }

    fun setBackgroundColor(argb: Int) = updateCanvas { it.copy(backgroundColorArgb = argb) }

    private fun updateCanvas(transform: (CanvasSettings) -> CanvasSettings) {
        val newCanvas = transform(_uiState.value.canvas)
        _uiState.update { it.copy(canvas = newCanvas) }
        loadedProject = loadedProject?.copy(canvas = newCanvas)
        scheduleSave()
    }

    fun setCrop(clipId: String, crop: CropRect) {
        mutate(structural = false) {
            TimelineEditor.updateClip(it, clipId) { c -> c.copy(crop = crop) }
        }
    }

    fun setCropPan(clipId: String, panX: Float, panY: Float) {
        mutate(structural = false) { TimelineEditor.setCropPan(it, clipId, panX, panY) }
    }

    // ------------------------------------------------------------------ keyframes

    fun addKeyframeAtPlayhead() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val timeInClip = (_uiState.value.playheadMs - clip.timelineStartMs).coerceAtLeast(0L)
        val kf = Keyframe(
            timeMs = timeInClip,
            offsetX = clip.transform.offsetX,
            offsetY = clip.transform.offsetY,
            scale = clip.transform.scale,
            rotationDegrees = clip.transform.rotationDegrees,
            opacity = clip.opacity,
        )
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c ->
                val others = c.keyframes.filter { abs(it.timeMs - timeInClip) > 50L }
                c.copy(keyframes = (others + kf).sortedBy { it.timeMs })
            }
        }
        showToast("Keyframe Added!")
    }

    fun toggleKeyframeAtPlayhead() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val timeInClip = _uiState.value.playheadMs - clip.timelineStartMs
        if (timeInClip < 0L || timeInClip > clip.timelineDurationMs) {
            showToast("Playhead is outside clip!")
            return@withSelected
        }
        val existingIdx = clip.keyframes.indexOfFirst { abs(it.timeMs - timeInClip) <= 200L }
        if (existingIdx >= 0 && clip.keyframes.size > 1) {
            mutate(structural = false) { timeline ->
                TimelineEditor.updateClip(timeline, id) { c ->
                    c.copy(keyframes = c.keyframes.filterIndexed { i, _ -> i != existingIdx })
                }
            }
            showToast("Keyframe Removed")
        } else {
            addKeyframeAtPlayhead()
        }
    }

    fun clearKeyframes() = withSelected { id ->
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c -> c.copy(keyframes = emptyList()) }
        }
    }

    fun setSpeedRamp(ramp: SpeedRamp) = withSelected { id ->
        mutate(structural = false) { timeline ->
            TimelineEditor.updateClip(timeline, id) { c -> c.copy(speedRamp = ramp) }
        }
    }

    fun addPipAtPlayhead(uri: String, isImage: Boolean = false) {
        viewModelScope.launch {
            val clip = if (isImage) {
                Clip(
                    id = Ids.clip(),
                    type = ClipType.IMAGE,
                    mediaUri = uri,
                    timelineStartMs = _uiState.value.playheadMs,
                    timelineEndMs = _uiState.value.playheadMs + 7_000L,
                    transform = Transform(offsetX = 0.4f, offsetY = -0.4f, scale = 0.4f),
                    keyframes = listOf(Keyframe(timeMs = 0L, offsetX = 0.4f, offsetY = -0.4f, scale = 0.4f)),
                )
            } else {
                val media = resolveMedia(uri)
                val base = ClipFactory.fromMedia(
                    (media ?: com.actioncut.core.model.MediaItem(uri, uri, MediaType.VIDEO, "PIP", 5_000L))
                        .copy(type = MediaType.VIDEO),
                    _uiState.value.playheadMs,
                )
                base.copy(
                    transform = Transform(offsetX = 0.4f, offsetY = -0.4f, scale = 0.4f),
                    keyframes = listOf(Keyframe(timeMs = 0L, offsetX = 0.4f, offsetY = -0.4f, scale = 0.4f)),
                )
            }
            val track = _uiState.value.timeline.tracks.firstOrNull { it.type == TrackType.OVERLAY }
            mutate(structural = !isImage) { timeline ->
                if (track != null) {
                    TimelineEditor.addClip(timeline, track.id, clip)
                } else {
                    val (withTrack, trackId) = TimelineEditor.addTrack(timeline, TrackType.OVERLAY)
                    TimelineEditor.addClip(withTrack, trackId, clip)
                }
            }
            _uiState.update { it.copy(selectedClipId = clip.id) }
            showToast("PIP Added!")
        }
    }

    // ------------------------------------------------------------------ property edits

    fun setVolume(volume: Float) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.setVolume(it, id, volume) }
    }

    fun rotateSelected() = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        if (clip.type == ClipType.STICKER || clip.type == ClipType.TEXT) {
            setOverlayRotation(id, clip.transform.rotationDegrees + 45f)
        } else {
            mutate(structural = false) { TimelineEditor.setRotation(it, id, clip.rotationDegrees + 90) }
        }
    }

    fun setFilter(filter: Filter?) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.setFilter(it, id, filter) }
    }

    fun setAdjustments(adjustments: ColorAdjustments) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.setAdjustments(it, id, adjustments) }
    }

    fun setTextProperties(text: TextProperties) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.updateClip(it, id) { clip -> clip.copy(text = text) } }
    }

    fun addEffect(effect: VisualEffect) = withSelected { id ->
        mutate(structural = false) { TimelineEditor.addEffect(it, id, effect) }
    }

    fun toggleEffect(type: VisualEffectType) = withSelected { id ->
        val clip = currentClip(id) ?: return@withSelected
        val existingIndex = clip.effects.indexOfFirst { it.type == type }
        mutate(structural = false) { timeline ->
            if (existingIndex >= 0) {
                TimelineEditor.removeEffect(timeline, id, existingIndex)
            } else {
                TimelineEditor.addEffect(timeline, id, VisualEffect(type))
            }
        }
    }

    // ------------------------------------------------------------------ undo / redo

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(_uiState.value.timeline)
        val previous = undoStack.removeLast()
        applyTimeline(previous, structural = true)
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(_uiState.value.timeline)
        val next = redoStack.removeLast()
        applyTimeline(next, structural = true)
    }

    // ------------------------------------------------------------------ internals

    private inline fun withSelected(action: (String) -> Unit) {
        _uiState.value.selectedClipId?.let(action)
    }

    private fun currentClip(id: String) = _uiState.value.timeline.allClips.firstOrNull { it.id == id }

    private fun clipAtPlayhead(): Clip? {
        val playhead = _uiState.value.playheadMs
        return _uiState.value.timeline.videoTracks.firstOrNull()?.clipAt(playhead)
    }

    private fun mutate(structural: Boolean, op: (Timeline) -> Timeline) {
        val current = _uiState.value.timeline
        val updated = op(current)
        if (updated == current) return
        undoStack.addLast(current)
        if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        redoStack.clear()
        applyTimeline(updated, structural)
    }

    private fun applyTimeline(timeline: Timeline, structural: Boolean) {
        _uiState.update {
            it.copy(
                timeline = timeline,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                playheadMs = it.playheadMs.coerceIn(0, timeline.durationMs),
            )
        }
        if (structural) {
            playerController.setTimeline(timeline)
            playerController.seekTo(_uiState.value.playheadMs)
        } else {
            playerController.updateVolumes(timeline)
            playerController.refreshPreviewEffects(timeline)
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        val project = loadedProject ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(600)
            val updated = project.copy(timeline = _uiState.value.timeline)
            loadedProject = updated
            saveProject(updated)
        }
    }

    fun saveNow() {
        val project = loadedProject ?: return
        viewModelScope.launch {
            saveProject(project.copy(timeline = _uiState.value.timeline))
        }
    }

    override fun onCleared() {
        super.onCleared()
        saveNow()
        voiceRecorder.cancel()
        playerController.release()
    }

    companion object {
        const val ARG_PROJECT_ID = "projectId"
        private const val MAX_UNDO = 50
    }
}
