package com.actioncut.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.actioncut.core.designsystem.component.clickableNoRipple
import com.actioncut.core.designsystem.component.rememberHaptic

/**
 * Contextual clip action bar shown above the toolbar whenever a clip is selected.
 * Places [Replace] on the left of [Split], [Done] in the middle gap, and a yellow
 * [Capture] button on the far right (where Done used to sit).
 */
@Composable
fun SelectedClipBar(
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onDeselect: () -> Unit,
    modifier: Modifier = Modifier,
    onReplace: () -> Unit = {},
    showReplace: Boolean = true,
    onCapture: () -> Unit = {},
    showCapture: Boolean = true,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Selected clip actions" },
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showReplace) {
                ClipBarActionItem(label = "Replace", icon = Icons.Filled.SwapHoriz, onClick = onReplace)
            }
            ClipBarActionItem(label = "Split", icon = Icons.Filled.ContentCut, onClick = onSplit)
            ClipBarActionItem(label = "Duplicate", icon = Icons.Filled.ContentCopy, onClick = onDuplicate)
            ClipBarActionItem(label = "Delete", icon = Icons.Filled.DeleteOutline, onClick = onDelete)
            ClipBarActionItem(label = "Done", icon = Icons.Filled.Done, onClick = onDeselect)
            Spacer(Modifier.weight(1f, fill = true))
            if (showCapture) {
                ClipBarActionItem(
                    label = "Capture",
                    icon = Icons.Filled.CameraAlt,
                    accentColor = Color(0xFFFFE300),
                    onClick = onCapture,
                )
            }
        }
    }
}

@Composable
private fun ClipBarActionItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    accentColor: Color? = null,
) {
    val haptic = rememberHaptic()
    val tileBg = accentColor?.copy(alpha = 0.20f) ?: MaterialTheme.colorScheme.surfaceVariant
    val iconCol = accentColor ?: MaterialTheme.colorScheme.onSurface
    val labelCol = accentColor ?: MaterialTheme.colorScheme.onSurfaceVariant

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
                .then(
                    if (accentColor != null) {
                        Modifier.border(1.dp, accentColor, RoundedCornerShape(10.dp))
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = iconCol, modifier = Modifier.size(18.dp))
        }
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (accentColor != null) FontWeight.Bold else FontWeight.Medium,
            color = labelCol,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
