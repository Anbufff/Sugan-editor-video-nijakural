package com.actioncut.core.domain.editor

import com.actioncut.core.model.AudioFade
import com.actioncut.core.model.Clip
import com.actioncut.core.model.ClipType
import com.actioncut.core.model.ColorAdjustments
import com.actioncut.core.model.Filter
import com.actioncut.core.model.MainAnimation
import com.actioncut.core.model.SpeedPresets
import com.actioncut.core.model.Timeline
import com.actioncut.core.model.Track
import com.actioncut.core.model.TrackType
import com.actioncut.core.model.Transform
import com.actioncut.core.model.Transition
import com.actioncut.core.model.TransitionType
import com.actioncut.core.model.VisualEffect
import java.util.UUID

/**
 * Pure, side-effect-free editing engine. Every operation takes an immutable [Timeline]
 * and returns a new one, which makes the whole thing trivially unit-testable and a
 * perfect fit for an undo/redo stack in the ViewModel.
 */
object TimelineEditor {

    /** Minimum length any clip may be trimmed/split to. */
    const val MIN_CLIP_DURATION_MS = 100L

    // ---------------------------------------------------------------------------------
    // Track operations
    // ---------------------------------------------------------------------------------

    fun addTrack(timeline: Timeline, type: TrackType): Pair<Timeline, String> {
        val id = "trk_${UUID.randomUUID().toString().take(8)}"
        val track = Track(id = id, type = type, index = timeline.tracks.size)
        return timeline.copy(tracks = timeline.tracks + track) to id
    }

    fun removeTrack(timeline: Timeline, trackId: String): Timeline =
        timeline.copy(tracks = timeline.tracks.filterNot { it.id == trackId })

    // ---------------------------------------------------------------------------------
    // Structural clip operations
    // ---------------------------------------------------------------------------------

    fun appendClip(timeline: Timeline, trackId: String, clip: Clip): Timeline {
        val track = timeline.track(trackId) ?: return timeline
        val start = track.durationMs
        val placed = clip.copy(
            timelineStartMs = start,
            timelineEndMs = start + clip.timelineDurationMs,
        )
        return mapTrack(timeline, trackId) { it.copy(clips = it.clips + placed) }
    }

    fun addClip(timeline: Timeline, trackId: String, clip: Clip): Timeline =
        mapTrack(timeline, trackId) { t -> t.copy(clips = (t.clips + clip).sortedBy { it.timelineStartMs }) }

    fun insertClip(timeline: Timeline, trackId: String, clip: Clip): Timeline {
        return mapTrack(timeline, trackId) { track ->
            val shifted = track.clips.map { existing ->
                if (existing.timelineStartMs >= clip.timelineStartMs) {
                    existing.shiftedBy(clip.timelineDurationMs)
                } else {
                    existing
                }
            }
            track.copy(clips = (shifted + clip).sortedBy { it.timelineStartMs })
        }
    }

    fun removeClip(timeline: Timeline, clipId: String, ripple: Boolean = true): Timeline {
        val located = findClip(timeline, clipId) ?: return timeline
        val (track, clip) = located
        return mapTrack(timeline, track.id) { t ->
            val remaining = t.clips.filterNot { it.id == clipId }
            val result = if (ripple) {
                remaining.map { c ->
                    if (c.timelineStartMs >= clip.timelineEndMs) {
                        c.shiftedBy(-clip.timelineDurationMs)
                    } else {
                        c
                    }
                }
            } else {
                remaining
            }
            t.copy(clips = result)
        }
    }

    fun splitClip(timeline: Timeline, clipId: String, atTimelineMs: Long): Timeline {
        val located = findClip(timeline, clipId) ?: return timeline
        val (track, clip) = located
        if (atTimelineMs <= clip.timelineStartMs + MIN_CLIP_DURATION_MS) return timeline
        if (atTimelineMs >= clip.timelineEndMs - MIN_CLIP_DURATION_MS) return timeline

        val offsetTimeline = atTimelineMs - clip.timelineStartMs
        val sourceMid = sourceAt(clip, offsetTimeline)

        val first: Clip
        val second: Clip
        if (clip.hasSourceWindow) {
            if (clip.isReversed) {
                first = clip.copy(
                    id = newClipId(),
                    timelineEndMs = atTimelineMs,
                    sourceInMs = sourceMid,
                )
                second = clip.copy(
                    id = newClipId(),
                    timelineStartMs = atTimelineMs,
                    sourceOutMs = sourceMid,
                    transitionToNext = clip.transitionToNext,
                )
            } else {
                first = clip.copy(
                    id = newClipId(),
                    timelineEndMs = atTimelineMs,
                    sourceOutMs = sourceMid,
                    transitionToNext = null,
                )
                second = clip.copy(
                    id = newClipId(),
                    timelineStartMs = atTimelineMs,
                    sourceInMs = sourceMid,
                )
            }
        } else {
            first = clip.copy(id = newClipId(), timelineEndMs = atTimelineMs, transitionToNext = null)
            second = clip.copy(id = newClipId(), timelineStartMs = atTimelineMs)
        }

        return mapTrack(timeline, track.id) { t ->
            t.copy(clips = t.clips.flatMap { if (it.id == clipId) listOf(first, second) else listOf(it) })
        }
    }

    fun trimClipStart(timeline: Timeline, clipId: String, newStartMs: Long): Timeline {
        val (_, clip) = findClip(timeline, clipId) ?: return timeline
        if (clip.isLocked) return timeline
        val clamped = newStartMs.coerceIn(
            (clip.timelineStartMs - headRoom(clip)),
            clip.timelineEndMs - MIN_CLIP_DURATION_MS,
        )
        val delta = clamped - clip.timelineStartMs
        val sourceDelta = (delta * clip.speed).toLong()
        return updateClip(timeline, clipId) { c ->
            if (!c.hasSourceWindow) {
                c.copy(timelineStartMs = clamped)
            } else if (c.isReversed) {
                c.copy(timelineStartMs = clamped, sourceOutMs = c.sourceOutMs - sourceDelta)
            } else {
                c.copy(timelineStartMs = clamped, sourceInMs = c.sourceInMs + sourceDelta)
            }
        }
    }

    fun trimClipEnd(timeline: Timeline, clipId: String, newEndMs: Long): Timeline {
        val (_, clip) = findClip(timeline, clipId) ?: return timeline
        if (clip.isLocked) return timeline
        val clamped = newEndMs.coerceIn(
            clip.timelineStartMs + MIN_CLIP_DURATION_MS,
            clip.timelineEndMs + tailRoom(clip),
        )
        val delta = clip.timelineEndMs - clamped
        val sourceDelta = (delta * clip.speed).toLong()
        return updateClip(timeline, clipId) { c ->
            if (!c.hasSourceWindow) {
                c.copy(timelineEndMs = clamped)
            } else if (c.isReversed) {
                c.copy(timelineEndMs = clamped, sourceInMs = c.sourceInMs + sourceDelta)
            } else {
                c.copy(timelineEndMs = clamped, sourceOutMs = c.sourceOutMs - sourceDelta)
            }
        }
    }

    /** Trims the right edge of [clipId] to [atTimelineMs] and ripples main-track clips left. */
    fun trimEndTo(timeline: Timeline, clipId: String, atTimelineMs: Long): Timeline {
        val (track, clip) = findClip(timeline, clipId) ?: return timeline
        if (clip.isLocked) return timeline
        if (atTimelineMs <= clip.timelineStartMs + MIN_CLIP_DURATION_MS || atTimelineMs >= clip.timelineEndMs) {
            return timeline
        }
        val oldEnd = clip.timelineEndMs
        val trimmed = trimClipEnd(timeline, clipId, atTimelineMs)
        if (track.type != TrackType.VIDEO) return trimmed
        val shift = atTimelineMs - oldEnd
        return mapTrack(trimmed, track.id) { t ->
            t.copy(clips = t.clips.map { c -> if (c.id != clipId && c.timelineStartMs >= oldEnd) c.shiftedBy(shift) else c })
        }
    }

    fun moveClip(
        timeline: Timeline,
        clipId: String,
        targetTrackId: String,
        newStartMs: Long,
    ): Timeline {
        val (sourceTrack, clip) = findClip(timeline, clipId) ?: return timeline
        if (clip.isLocked) return timeline
        if (timeline.track(targetTrackId) == null) return timeline
        val start = newStartMs.coerceAtLeast(0)
        val moved = clip.copy(
            timelineStartMs = start,
            timelineEndMs = start + clip.timelineDurationMs,
        )
        val withoutOriginal = mapTrack(timeline, sourceTrack.id) { t ->
            t.copy(clips = t.clips.filterNot { it.id == clipId })
        }
        return mapTrack(withoutOriginal, targetTrackId) { t ->
            t.copy(clips = (t.clips + moved).sortedBy { it.timelineStartMs })
        }
    }

    // ---------------------------------------------------------------------------------
    // Audio & Capture / HTML Editor features
    // ---------------------------------------------------------------------------------

    fun detachAudio(timeline: Timeline, clipId: String): Timeline {
        val located = findClip(timeline, clipId) ?: return timeline
        val (_, clip) = located
        if (clip.type != ClipType.VIDEO || clip.mediaUri == null) return timeline

        val audioClip = Clip(
            id = newClipId(),
            type = ClipType.AUDIO,
            mediaUri = clip.mediaUri,
            timelineStartMs = clip.timelineStartMs,
            timelineEndMs = clip.timelineEndMs,
            sourceInMs = clip.sourceInMs,
            sourceOutMs = clip.sourceOutMs,
            speed = clip.speed,
            isReversed = clip.isReversed,
            volume = if (clip.volume <= 0f) 1f else clip.volume,
        )

        val muted = updateClip(timeline, clipId) { it.copy(volume = 0f) }

        val audioTrack = muted.tracks.firstOrNull { it.type == TrackType.AUDIO }
        return if (audioTrack != null) {
            mapTrack(muted, audioTrack.id) { t ->
                t.copy(clips = (t.clips + audioClip).sortedBy { it.timelineStartMs })
            }
        } else {
            val (withTrack, trackId) = addTrack(muted, TrackType.AUDIO)
            mapTrack(withTrack, trackId) { t -> t.copy(clips = t.clips + audioClip) }
        }
    }

    fun setAudioLocked(timeline: Timeline, clipId: String, locked: Boolean): Timeline =
        updateClip(timeline, clipId) { it.copy(isLocked = locked) }

    fun addCaptureMark(timeline: Timeline, clipId: String, relativeMs: Long): Timeline =
        updateClip(timeline, clipId) { c ->
            c.copy(captureMarksMs = (c.captureMarksMs + relativeMs.coerceAtLeast(0L)).distinct().sorted())
        }

    /** Removes all VIDEO clips from main video lanes and packs remaining IMAGE screenshots contiguously. */
    fun deleteAllVideosKeepImages(timeline: Timeline): Timeline {
        val mainVideoIds = timeline.videoTracks.map { it.id }.toSet()
        return timeline.copy(
            tracks = timeline.tracks.map { track ->
                if (track.id !in mainVideoIds) {
                    track
                } else {
                    var cursor = 0L
                    val kept = track.clips
                        .filter { it.type != ClipType.VIDEO }
                        .map { clip ->
                            val dur = clip.timelineDurationMs.coerceAtLeast(MIN_CLIP_DURATION_MS)
                            val placed = clip.copy(
                                timelineStartMs = cursor,
                                timelineEndMs = cursor + dur,
                                transitionToNext = null,
                            )
                            cursor += dur
                            placed
                        }
                    track.copy(clips = kept)
                }
            },
        )
    }

    fun setDuration(timeline: Timeline, clipId: String, durationMs: Long, ripple: Boolean = true): Timeline {
        val (track, clip) = findClip(timeline, clipId) ?: return timeline
        val safeDur = durationMs.coerceAtLeast(MIN_CLIP_DURATION_MS)
        val delta = safeDur - clip.timelineDurationMs
        if (delta == 0L) return timeline
        val oldEnd = clip.timelineEndMs
        val updated = updateClip(timeline, clipId) { c ->
            c.copy(timelineEndMs = c.timelineStartMs + safeDur)
        }
        if (!ripple || track.type != TrackType.VIDEO) return updated
        return mapTrack(updated, track.id) { t ->
            t.copy(clips = t.clips.map { c -> if (c.id != clipId && c.timelineStartMs >= oldEnd) c.shiftedBy(delta) else c })
        }
    }

    fun setDurationAll(timeline: Timeline, durationMs: Long): Timeline {
        val mainTrack = timeline.videoTracks.firstOrNull() ?: return timeline
        val safeDur = durationMs.coerceAtLeast(MIN_CLIP_DURATION_MS)
        var cursor = 0L
        val packed = mainTrack.clips.map { clip ->
            val placed = clip.copy(timelineStartMs = cursor, timelineEndMs = cursor + safeDur)
            cursor += safeDur
            placed
        }
        return mapTrack(timeline, mainTrack.id) { it.copy(clips = packed) }
    }

    fun setSpeedKeepDuration(timeline: Timeline, clipId: String, speed: Float): Timeline {
        val s = speed.coerceIn(SpeedPresets.MIN, SpeedPresets.MAX)
        return updateClip(timeline, clipId) { it.copy(speed = s) }
    }

    fun setSpeedKeepDurationAll(timeline: Timeline, speed: Float): Timeline {
        val s = speed.coerceIn(SpeedPresets.MIN, SpeedPresets.MAX)
        val mainTrack = timeline.videoTracks.firstOrNull() ?: return timeline
        return mapTrack(timeline, mainTrack.id) { t ->
            t.copy(clips = t.clips.map { c -> if (c.type == ClipType.VIDEO) c.copy(speed = s) else c })
        }
    }

    fun extendOverlayToClipEnd(timeline: Timeline, clipId: String): Timeline {
        val (_, clip) = findClip(timeline, clipId) ?: return timeline
        val mainTrack = timeline.videoTracks.firstOrNull() ?: return timeline
        val host = mainTrack.clipAt(clip.timelineStartMs)
            ?: mainTrack.clips.firstOrNull { it.timelineEndMs > clip.timelineStartMs }
            ?: return timeline
        if (host.timelineEndMs <= clip.timelineStartMs) return timeline
        return updateClip(timeline, clipId) { it.copy(timelineEndMs = host.timelineEndMs) }
    }

    fun extendOverlayToVideoEnd(timeline: Timeline, clipId: String): Timeline {
        val (_, clip) = findClip(timeline, clipId) ?: return timeline
        val totalEnd = timeline.videoTracks.firstOrNull()?.durationMs ?: return timeline
        if (totalEnd <= clip.timelineStartMs) return timeline
        return updateClip(timeline, clipId) { it.copy(timelineEndMs = totalEnd) }
    }

    fun replaceClipMedia(
        timeline: Timeline,
        clipId: String,
        newUri: String,
        newType: ClipType,
        newSourceDurationMs: Long,
    ): Timeline = updateClip(timeline, clipId) { c ->
        if (newType == ClipType.IMAGE) {
            c.copy(type = ClipType.IMAGE, mediaUri = newUri, sourceInMs = 0L, sourceOutMs = 0L, captureMarksMs = emptyList())
        } else {
            val dur = newSourceDurationMs.coerceAtLeast(MIN_CLIP_DURATION_MS)
            c.copy(type = newType, mediaUri = newUri, sourceInMs = 0L, sourceOutMs = dur, timelineEndMs = c.timelineStartMs + dur, captureMarksMs = emptyList())
        }
    }

    // ---------------------------------------------------------------------------------
    // Property updates
    // ---------------------------------------------------------------------------------

    fun updateClip(timeline: Timeline, clipId: String, transform: (Clip) -> Clip): Timeline =
        timeline.copy(
            tracks = timeline.tracks.map { track ->
                if (track.clips.none { it.id == clipId }) {
                    track
                } else {
                    track.copy(clips = track.clips.map { if (it.id == clipId) transform(it) else it })
                }
            },
        )

    fun setSpeed(timeline: Timeline, clipId: String, speed: Float): Timeline {
        val s = speed.coerceIn(SpeedPresets.MIN, SpeedPresets.MAX)
        return updateClip(timeline, clipId) { c ->
            if (!c.hasSourceWindow) {
                c.copy(speed = s)
            } else {
                val newDuration = (c.sourceDurationMs / s).toLong().coerceAtLeast(MIN_CLIP_DURATION_MS)
                c.copy(speed = s, timelineEndMs = c.timelineStartMs + newDuration)
            }
        }
    }

    fun setReversed(timeline: Timeline, clipId: String, reversed: Boolean): Timeline =
        updateClip(timeline, clipId) { it.copy(isReversed = reversed) }

    fun setVolume(timeline: Timeline, clipId: String, volume: Float): Timeline =
        updateClip(timeline, clipId) { it.copy(volume = volume.coerceIn(0f, 2f)) }

    fun setRotation(timeline: Timeline, clipId: String, degrees: Int): Timeline =
        updateClip(timeline, clipId) { it.copy(rotationDegrees = ((degrees % 360) + 360) % 360) }

    fun setOpacity(timeline: Timeline, clipId: String, opacity: Float): Timeline =
        updateClip(timeline, clipId) { it.copy(opacity = opacity.coerceIn(0f, 1f)) }

    fun setAdjustments(timeline: Timeline, clipId: String, adjustments: ColorAdjustments): Timeline =
        updateClip(timeline, clipId) { it.copy(adjustments = adjustments) }

    fun setFilter(timeline: Timeline, clipId: String, filter: Filter?): Timeline =
        updateClip(timeline, clipId) { it.copy(filter = filter) }

    fun setTransform(timeline: Timeline, clipId: String, transform: Transform): Timeline =
        updateClip(timeline, clipId) { it.copy(transform = transform) }

    fun setAudioFade(timeline: Timeline, clipId: String, fade: AudioFade): Timeline =
        updateClip(timeline, clipId) { it.copy(audioFade = fade) }

    fun setTransition(timeline: Timeline, clipId: String, transition: Transition?): Timeline =
        updateClip(timeline, clipId) { it.copy(transitionToNext = transition) }

    fun setTransitionAll(timeline: Timeline, transition: Transition?): Timeline {
        val mainTrack = timeline.videoTracks.firstOrNull() ?: return timeline
        return mapTrack(timeline, mainTrack.id) { t ->
            t.copy(clips = t.clips.mapIndexed { idx, c ->
                if (idx < t.clips.lastIndex) c.copy(transitionToNext = transition) else c
            })
        }
    }

    fun setTransitionsRandom(timeline: Timeline, pool: List<TransitionType>): Timeline {
        if (pool.isEmpty()) return timeline
        val mainTrack = timeline.videoTracks.firstOrNull() ?: return timeline
        return mapTrack(timeline, mainTrack.id) { t ->
            t.copy(clips = t.clips.mapIndexed { idx, c ->
                if (idx < t.clips.lastIndex) c.copy(transitionToNext = Transition(pool.random())) else c
            })
        }
    }

    fun setMainAnimation(timeline: Timeline, clipId: String, animation: MainAnimation): Timeline =
        updateClip(timeline, clipId) { it.copy(mainAnimation = animation) }

    fun setMainAnimationAll(timeline: Timeline, animation: MainAnimation): Timeline {
        val mainTrack = timeline.videoTracks.firstOrNull() ?: return timeline
        return mapTrack(timeline, mainTrack.id) { t ->
            t.copy(clips = t.clips.map { it.copy(mainAnimation = animation) })
        }
    }

    fun setMainAnimationRandom(timeline: Timeline, pool: List<MainAnimation>): Timeline {
        if (pool.isEmpty()) return timeline
        val mainTrack = timeline.videoTracks.firstOrNull() ?: return timeline
        return mapTrack(timeline, mainTrack.id) { t ->
            t.copy(clips = t.clips.map { it.copy(mainAnimation = pool.random()) })
        }
    }

    fun setFlipHorizontal(timeline: Timeline, clipId: String, flipped: Boolean): Timeline =
        updateClip(timeline, clipId) { it.copy(flipHorizontal = flipped) }

    fun setCutout(timeline: Timeline, clipId: String, enabled: Boolean): Timeline =
        updateClip(timeline, clipId) { it.copy(cutoutEnabled = enabled) }

    fun setCropPan(timeline: Timeline, clipId: String, panX: Float, panY: Float): Timeline =
        updateClip(timeline, clipId) { it.copy(cropPanX = panX.coerceIn(0f, 1f), cropPanY = panY.coerceIn(0f, 1f)) }

    fun addEffect(timeline: Timeline, clipId: String, effect: VisualEffect): Timeline =
        updateClip(timeline, clipId) { it.copy(effects = it.effects + effect) }

    fun removeEffect(timeline: Timeline, clipId: String, index: Int): Timeline =
        updateClip(timeline, clipId) { c ->
            if (index in c.effects.indices) c.copy(effects = c.effects.filterIndexed { i, _ -> i != index }) else c
        }

    // ---------------------------------------------------------------------------------
    // Lookup helpers
    // ---------------------------------------------------------------------------------

    fun findClip(timeline: Timeline, clipId: String): Pair<Track, Clip>? {
        for (track in timeline.tracks) {
            val clip = track.clips.firstOrNull { it.id == clipId }
            if (clip != null) return track to clip
        }
        return null
    }

    // ---------------------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------------------

    private fun newClipId(): String = "clp_${UUID.randomUUID().toString().take(8)}"

    private fun mapTrack(timeline: Timeline, trackId: String, transform: (Track) -> Track): Timeline =
        timeline.copy(tracks = timeline.tracks.map { if (it.id == trackId) transform(it) else it })

    private fun sourceAt(clip: Clip, offsetTimelineMs: Long): Long {
        val sourceOffset = (offsetTimelineMs * clip.speed).toLong()
        return if (clip.isReversed) clip.sourceOutMs - sourceOffset else clip.sourceInMs + sourceOffset
    }

    private fun headRoom(clip: Clip): Long {
        if (!clip.hasSourceWindow) return clip.timelineStartMs
        val availableSource = if (clip.isReversed) Long.MAX_VALUE else clip.sourceInMs
        return (availableSource / clip.speed).toLong().coerceAtMost(clip.timelineStartMs)
    }

    private fun tailRoom(clip: Clip): Long {
        if (!clip.hasSourceWindow) return Long.MAX_VALUE / 2
        return (clip.sourceDurationMs / clip.speed).toLong()
    }

    private fun Clip.shiftedBy(deltaMs: Long): Clip =
        copy(timelineStartMs = timelineStartMs + deltaMs, timelineEndMs = timelineEndMs + deltaMs)

    private val Clip.hasSourceWindow: Boolean
        get() = type == ClipType.VIDEO || type == ClipType.AUDIO
}
