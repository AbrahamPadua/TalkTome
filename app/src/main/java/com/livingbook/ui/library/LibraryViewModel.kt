package com.livingbook.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livingbook.data.ReadingRepository
import com.livingbook.data.db.LibraryRow
import com.livingbook.doc.DocumentException
import com.livingbook.doc.DocumentImporter
import com.livingbook.session.ReadingSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Library list, import and delete. [session] is a lambda so opening the library does not start the narration engine. */
class LibraryViewModel(
    private val reading: ReadingRepository,
    private val importer: DocumentImporter,
    private val session: () -> ReadingSession,
) : ViewModel() {
    /** Null until the first database read, so the empty state never flashes for a full library. */
    val books: StateFlow<List<LibraryRow>?> = reading.observeLibrary().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun import(uri: Uri) {
        if (_importing.value) return
        _importing.value = true
        viewModelScope.launch {
            try {
                importer.import(uri)
            } catch (e: DocumentException) {
                _error.value = e.userMessage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "Couldn't read this file. Check that it opens on your phone, then try importing it again."
            } finally {
                _importing.value = false
            }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    fun delete(bookId: String) {
        // Narration would otherwise keep saving progress for a book that no longer exists.
        session().let { if (it.state.value.book?.id == bookId) it.close() }
        viewModelScope.launch { reading.deleteBook(bookId) }
    }
}
