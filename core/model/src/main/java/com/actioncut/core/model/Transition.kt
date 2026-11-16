package com.actioncut.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TransitionGroup(val displayName: String) {
    NONE("None"),
    SLIDE("Slide"),
    MOVE("Move"),
    WIPE("Wipe"),
    MIX("Mix"),
    SEAMLESS("Seamless"),
}

/** Available transition styles blended between two adjacent clips. */
@Serializable
enum class TransitionType(
    val displayName: String,
    val group: TransitionGroup = TransitionGroup.MIX,
) {
    NONE("None", TransitionGroup.NONE),
    FADE("Fade", TransitionGroup.MIX),
    DISSOLVE("Dissolve", TransitionGroup.MIX),
    CROSSFADE("Crossfade", TransitionGroup.MIX),
    SLIDE_LEFT("Slide L", TransitionGroup.SLIDE),
    SLIDE_RIGHT("Slide R", TransitionGroup.SLIDE),
    SLIDE_UP("Slide U", TransitionGroup.SLIDE),
    SLIDE_DOWN("Slide D", TransitionGroup.SLIDE),
    MOVE_LEFT("Move L", TransitionGroup.MOVE),
    MOVE_RIGHT("Move R", TransitionGroup.MOVE),
    MOVE_UP("Move U", TransitionGroup.MOVE),
    MOVE_DOWN("Move D", TransitionGroup.MOVE),
    WIPE("Wipe", TransitionGroup.WIPE),
    WIPE_LEFT("Wipe L", TransitionGroup.WIPE),
    WIPE_RIGHT("Wipe R", TransitionGroup.WIPE),
    WIPE_UP("Wipe U", TransitionGroup.WIPE),
    WIPE_DOWN("Wipe D", TransitionGroup.WIPE),
    ZOOM_IN("Zoom In", TransitionGroup.MIX),
    ZOOM_OUT("Zoom Out", TransitionGroup.MIX),
    BLUR("Blur", TransitionGroup.MIX),
    SEAMLESS("Seamless Merge", TransitionGroup.SEAMLESS),
}

/**
 * A transition placed at the boundary between a clip and the next one on the same track.
 *
 * @property durationMs How long the blend lasts (overlaps both clips by half).
 */
@Serializable
data class Transition(
    val type: TransitionType,
    val durationMs: Long = 400L,
) {
    companion object {
        val NONE = Transition(TransitionType.NONE, 0L)
        const val DEFAULT_DURATION_MS = 400L
    }
}
