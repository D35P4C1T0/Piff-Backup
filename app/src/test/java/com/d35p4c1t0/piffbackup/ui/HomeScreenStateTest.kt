package com.d35p4c1t0.piffbackup.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HomeScreenStateTest {
    @Test
    fun validCheckpointLoadsBackedUpStateWithDynamicMappingCount() {
        val state = HomeScreenState.loaded(
            mappingCount = 17,
            lastSuccessfulBackupAtEpochMillis = 1_234L,
            hasCurrentCheckpoint = true,
        )

        assertEquals(HomeBackupStatus.EVERYTHING_BACKED_UP, state.status)
        assertEquals(17, state.mappingCount)
        assertEquals(1_234L, state.lastSuccessfulBackupAtEpochMillis)
    }

    @Test
    fun invalidatedCheckpointNeedsAttentionWithoutLosingLastSuccess() {
        val state = HomeScreenState.loaded(
            mappingCount = 2,
            lastSuccessfulBackupAtEpochMillis = 5_000L,
            hasCurrentCheckpoint = false,
        )

        assertEquals(HomeBackupStatus.NEEDS_ATTENTION, state.status)
        assertEquals(5_000L, state.lastSuccessfulBackupAtEpochMillis)
    }

    @Test
    fun readyAndProgressStatesRejectImpossibleValues() {
        assertThrows(IllegalArgumentException::class.java) {
            HomeScreenState(HomeBackupStatus.NEW_ITEMS_READY, 1, null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HomeScreenState(HomeBackupStatus.BACKING_UP, 1, null, progressPercentage = 101)
        }
    }

    @Test
    fun readyAndBackingUpStatesRetainCalculatedTotals() {
        val ready = HomeScreenState(
            status = HomeBackupStatus.NEW_ITEMS_READY,
            mappingCount = 3,
            lastSuccessfulBackupAtEpochMillis = 10L,
            changedItems = 2_500L,
            changedBytes = 8_000_000L,
        )
        val backingUp = ready.copy(
            status = HomeBackupStatus.BACKING_UP,
            progressPercentage = 63,
        )

        assertEquals(2_500L, backingUp.changedItems)
        assertEquals(8_000_000L, backingUp.changedBytes)
        assertEquals(63, backingUp.progressPercentage)
    }
}
