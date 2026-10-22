package com.livingbook.ui.settings

import com.livingbook.data.db.LearningSignalEntity
import com.livingbook.data.db.SignalKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileLogicTest {
    private fun signal(id: Long, kind: SignalKind) = LearningSignalEntity(id = id, kind = kind, label = "l$id", evidence = "e", updatedAt = 0)

    @Test
    fun groupsInFixedOrderAndSkipsEmptyKinds() {
        val groups = groupSignals(listOf(signal(1, SignalKind.UNDERSTOOD), signal(2, SignalKind.PREFERENCE), signal(3, SignalKind.UNDERSTOOD)))
        assertEquals(listOf(SignalKind.PREFERENCE, SignalKind.UNDERSTOOD), groups.map { it.first })
        assertEquals(listOf(1L, 3L), groups[1].second.map { it.id })
        assertEquals(listOf("Preferences", "Needs another look", "Understood"), SignalKind.entries.map { it.title() })
    }
}
