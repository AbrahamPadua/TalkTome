package com.livingbook.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.livingbook.data.ReadingRepository
import com.livingbook.data.db.AnnotationEntity
import com.livingbook.data.db.AnnotationKind
import com.livingbook.data.db.BlockEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Annotations of the open book. Everything else the reader shows comes from [com.livingbook.session.ReadingSession]. */
class ReaderViewModel(private val bookId: String, private val reading: ReadingRepository) : ViewModel() {
    val annotations: StateFlow<List<AnnotationEntity>> =
        reading.observeAnnotations(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun annotate(kind: AnnotationKind, blockIndex: Int, text: String) {
        viewModelScope.launch {
            reading.addAnnotation(
                AnnotationEntity(bookId = bookId, kind = kind, blockIndex = blockIndex, text = text, createdAt = System.currentTimeMillis())
            )
        }
    }

    fun delete(annotation: AnnotationEntity) {
        viewModelScope.launch { reading.deleteAnnotation(annotation.id) }
    }

    suspend fun blocks(indices: List<Int>): List<BlockEntity> = reading.blocksAt(bookId, indices)
}
