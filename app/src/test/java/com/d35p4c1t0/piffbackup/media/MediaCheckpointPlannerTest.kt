package com.d35p4c1t0.piffbackup.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCheckpointPlannerTest {
    private val checkpoint = MediaStoreCheckpoint("external_primary", "v1", 10L)

    @Test
    fun `matching stable snapshot creates bounded incremental window`() {
        val decision = MediaCheckpointPlanner.decide(
            checkpoint,
            MediaStoreSnapshot("external_primary", "v1", 25L),
        )

        assertEquals(
            MediaCheckpointDecision.Incremental(MediaGenerationWindow(10L, 25L)),
            decision,
        )
    }

    @Test
    fun `missing or incompatible checkpoints require full reconciliation`() {
        val cases = listOf(
            null to FullReconciliationReason.NO_CHECKPOINT,
            checkpoint.copy(volumeName = "other") to FullReconciliationReason.VOLUME_CHANGED,
            checkpoint.copy(version = "old") to FullReconciliationReason.VERSION_CHANGED,
            checkpoint.copy(successfulGeneration = 30L) to FullReconciliationReason.GENERATION_REWOUND,
        )

        cases.forEach { (candidate, expectedReason) ->
            val decision = MediaCheckpointPlanner.decide(
                candidate,
                MediaStoreSnapshot("external_primary", "v1", 25L),
            )
            assertEquals(
                MediaCheckpointDecision.FullReconciliationRequired(expectedReason),
                decision,
            )
        }
    }

    @Test
    fun `version changing around generation snapshot is unstable`() {
        val decision = MediaCheckpointPlanner.decide(
            checkpoint,
            MediaStoreSnapshot("external_primary", "v2", 25L, stable = false),
        )

        assertTrue(decision is MediaCheckpointDecision.FullReconciliationRequired)
        assertEquals(
            FullReconciliationReason.UNSTABLE_SNAPSHOT,
            (decision as MediaCheckpointDecision.FullReconciliationRequired).reason,
        )
    }

    @Test
    fun `partial media permission cannot advance an incremental checkpoint`() {
        val decision = MediaCheckpointPlanner.decide(
            checkpoint,
            MediaStoreSnapshot(
                "external_primary",
                "v1",
                25L,
                accessScope = MediaAccessScope.PARTIAL,
            ),
        )

        assertEquals(
            MediaCheckpointDecision.FullReconciliationRequired(
                FullReconciliationReason.MEDIA_ACCESS_INCOMPLETE,
            ),
            decision,
        )
    }
}
