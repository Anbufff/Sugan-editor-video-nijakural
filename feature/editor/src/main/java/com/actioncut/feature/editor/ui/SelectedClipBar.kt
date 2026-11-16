package com.actioncut.feature.editor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.actioncut.core.designsystem.component.ToolItem

/**
 * Contextual clip action bar shown above the toolbar whenever a clip is selected.
 * Places [Replace] directly on the left of [Split] (replacing the old "Clip" label).
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
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Selected clip actions" },
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (showReplace) {
                ToolItem(label = "Replace", icon = Icons.Filled.SwapHoriz, onClick = onReplace)
            }
            ToolItem(label = "Split", icon = Icons.Filled.ContentCut, onClick = onSplit)
            ToolItem(label = "Duplicate", icon = Icons.Filled.ContentCopy, onClick = onDuplicate)
            ToolItem(label = "Delete", icon = Icons.Filled.DeleteOutline, onClick = onDelete)
            Spacer(Modifier.weight(1f))
            ToolItem(label = "Done", icon = Icons.Filled.Done, onClick = onDeselect)
        }
    }
}
