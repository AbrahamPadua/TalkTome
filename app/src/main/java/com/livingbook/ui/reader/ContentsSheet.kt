package com.livingbook.ui.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.livingbook.data.db.AnnotationEntity
import com.livingbook.data.db.AnnotationKind
import com.livingbook.model.Granularity
import com.livingbook.session.SessionState

/** Table of contents, topic size, and the reader's bookmarks, highlights and notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
    state: SessionState,
    annotations: List<AnnotationEntity>,
    onDismiss: () -> Unit,
    onTopic: (Int) -> Unit,
    onGranularity: (Granularity) -> Unit,
    onAnnotation: (AnnotationEntity) -> Unit,
    onDeleteAnnotation: (AnnotationEntity) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Contents") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Notes and bookmarks") })
        }
        if (tab == 0) TopicList(state, onTopic, onGranularity) else AnnotationList(annotations, onAnnotation, onDeleteAnnotation)
    }
}

@Composable
private fun TopicList(state: SessionState, onTopic: (Int) -> Unit, onGranularity: (Granularity) -> Unit) {
    val rows = remember(state.topics) { contentsRows(state.topics) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = rows.indexOfFirst { it is ContentsRow.Entry && it.topicIndex == state.topicIndex }.coerceAtLeast(0)
    )
    Column {
        Text(
            "Topic size",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Granularity.entries.forEachIndexed { i, granularity ->
                SegmentedButton(
                    selected = state.granularity == granularity,
                    onClick = { onGranularity(granularity) },
                    shape = SegmentedButtonDefaults.itemShape(i, Granularity.entries.size),
                ) { Text(granularity.label, maxLines = 1) }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.padding(top = 8.dp)) {
            items(rows) { row ->
                when (row) {
                    is ContentsRow.Section -> Text(
                        row.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 4.dp),
                    )
                    is ContentsRow.Entry -> {
                        val current = row.topicIndex == state.topicIndex
                        ListItem(
                            headlineContent = { Text(row.topic.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { Text("${row.topicIndex + 1}", style = MaterialTheme.typography.labelLarge) },
                            colors = ListItemDefaults.colors(
                                containerColor = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            ),
                            modifier = Modifier.selectable(selected = current, onClick = { onTopic(row.topicIndex) }),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnotationList(
    annotations: List<AnnotationEntity>,
    onAnnotation: (AnnotationEntity) -> Unit,
    onDelete: (AnnotationEntity) -> Unit,
) {
    if (annotations.isEmpty()) {
        Text(
            "Long-press a passage to highlight or bookmark it. The bookmark button at the top saves your current place.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
        return
    }
    LazyColumn {
        items(annotations, key = { it.id }) { annotation ->
            val kind = when (annotation.kind) {
                AnnotationKind.BOOKMARK -> "Bookmark"
                AnnotationKind.HIGHLIGHT -> "Highlight"
                AnnotationKind.NOTE -> "Note"
            }
            ListItem(
                headlineContent = { Text(annotation.text, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(annotation.note?.let { "$kind: $it" } ?: kind, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    Icon(
                        when (annotation.kind) {
                            AnnotationKind.BOOKMARK -> Icons.Outlined.BookmarkBorder
                            AnnotationKind.HIGHLIGHT -> Icons.Outlined.BorderColor
                            AnnotationKind.NOTE -> Icons.AutoMirrored.Outlined.StickyNote2
                        },
                        contentDescription = null,
                    )
                },
                trailingContent = {
                    IconButton(onClick = { onDelete(annotation) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete ${kind.lowercase()}") }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onAnnotation(annotation) },
            )
        }
    }
}
