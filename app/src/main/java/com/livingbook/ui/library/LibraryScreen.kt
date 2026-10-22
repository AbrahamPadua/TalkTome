package com.livingbook.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.livingbook.AppContainer
import com.livingbook.data.db.LibraryRow

private val BOOK_TYPES = arrayOf("application/pdf", "application/epub+zip")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(container: AppContainer, onOpenBook: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm = viewModel { LibraryViewModel(container.reading, container.importer) { container.session } }
    val books by vm.books.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }
    val pickBook = { if (!importing) picker.launch(BOOK_TYPES) }
    var confirmDelete by remember { mutableStateOf<LibraryRow?>(null) }
    var notesFor by remember { mutableStateOf<LibraryRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (!books.isNullOrEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = pickBook,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Import") },
                )
            }
        },
    ) { padding ->
        val list = books
        when {
            list == null -> Unit
            list.isEmpty() -> EmptyLibrary(importing, pickBook, Modifier.padding(padding))
            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = padding.calculateTopPadding() + 8.dp,
                        bottom = padding.calculateBottomPadding() + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (importing) item(key = "importing") { ImportingRow() }
                    continueBook(list)?.let { recent ->
                        item(key = "continue") { ContinueCard(recent, onOpenBook) }
                    }
                    item(key = "header") {
                        Text(
                            "Your books",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(list, key = { it.id }) { row ->
                        BookRow(row, onOpenBook, onDelete = { confirmDelete = row }, onShowNotes = { notesFor = row })
                    }
                }
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Couldn't import this book") },
            text = { Text(message) },
        )
    }
    confirmDelete?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            confirmButton = {
                TextButton(
                    onClick = { vm.delete(row.id); confirmDelete = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
            title = { Text("Delete “${row.title}”?") },
            text = { Text("This removes the book, your progress, notes and conversations about it from this device.") },
        )
    }
    notesFor?.let { row ->
        AlertDialog(
            onDismissRequest = { notesFor = null },
            confirmButton = { TextButton(onClick = { notesFor = null }) { Text("OK") } },
            title = { Text("Import notes") },
            text = { Text(row.warningList().joinToString("\n\n")) },
        )
    }
}

@Composable
private fun EmptyLibrary(importing: Boolean, onImport: () -> Unit, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "Turn a PDF or EPUB into short topics you can read, listen to and talk through.",
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Your books stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (importing) {
            ImportingRow(Modifier.widthIn(max = 320.dp))
        } else {
            Button(onClick = onImport) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Import a book")
            }
        }
    }
}

@Composable
private fun ImportingRow(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Importing your book. Large files can take a minute.", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ContinueCard(row: LibraryRow, onOpen: (String) -> Unit) {
    Card(
        onClick = { onOpen(row.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Cover(row.title, width = 72.dp, height = 100.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Pick up where you left off", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Progress(row)
                Button(onClick = { onOpen(row.id) }, modifier = Modifier.padding(top = 8.dp)) { Text("Continue reading") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(row: LibraryRow, onOpen: (String) -> Unit, onDelete: () -> Unit, onShowNotes: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(onClick = { onOpen(row.id) }, onLongClick = { menuOpen = true }, onLongClickLabel = "Book options"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(row.title, width = 48.dp, height = 66.dp)
            Column(Modifier.weight(1f)) {
                Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    row.author ?: row.format.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Progress(row)
            }
            if (row.warningList().isNotEmpty()) {
                IconButton(onClick = onShowNotes) {
                    Icon(Icons.Outlined.Info, contentDescription = "Import notes for ${row.title}", tint = MaterialTheme.colorScheme.secondary)
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "Options for ${row.title}") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
                        onClick = { menuOpen = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(row: LibraryRow) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(
            progress = { progressFraction(row.blockIndex, row.blockCount) },
            modifier = Modifier.weight(1f),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        Text(progressLabel(row), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Decorative generated cover; the title is read out by the row, so the initials are hidden from screen readers. */
@Composable
private fun Cover(title: String, width: Dp, height: Dp) {
    // Sized in dp, not sp: decoration should not grow out of the cover with large system fonts.
    val initialsSize = with(LocalDensity.current) { (width * 0.38f).toSp() }
    Box(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(6.dp))
            .background(coverColor(title))
            .clearAndSetSemantics {},
        contentAlignment = Alignment.Center,
    ) {
        Text(coverInitials(title), color = Color(0xFFFBF7F0), fontFamily = FontFamily.Serif, fontSize = initialsSize)
    }
}
