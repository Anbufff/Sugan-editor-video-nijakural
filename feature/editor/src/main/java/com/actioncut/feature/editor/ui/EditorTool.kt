package com.actioncut.feature.editor.ui

/**
 * Editing tools shown in the bottom toolbar. Includes all original ActionCut tools plus
 * every button/feature from the HTML editor.
 */
enum class EditorTool(val label: String, val opensPanel: Boolean) {
    SPLIT("Split", false),
    DELETE("Delete", false),
    ANIMATION("Animation", true),
    DURATION("Duration", true),
    TRIM("Trim", true),
    TRIM_R("Trim R", false),
    SPEED("Speed", true),
    VOLUME("Volume", true),
    MUTE("Mute", false),
    AUDIO("Audio", false),
    MUSIC("Music", true),
    VOICEOVER("Voiceover", true),
    SUBSCRIBE("Subscribe", false),
    LT("LT", true),
    PIP("PiP", false),
    NEWS_ALERT("News Alert", true),
    ARROW("Arrow", false),
    CAPTURE("Capture", false),
    DEL_VIDEOS("Del Videos", false),
    EXTRACT_AUDIO("Detach", false),
    REVERSE("Reverse", false),
    ROTATE("Rotate", false),
    FILTERS("Filters", true),
    ADJUST("Adjust", true),
    CROP("Crop", true),
    CANVAS("Canvas", true),
    TEXT("Text", true),
    STICKER("Sticker", true),
    TRANSITIONS("Transition", true),
    EFFECTS("Effects", true),
    KEYFRAME("Keyframe", true),
    SETTINGS("Settings", true),
}
