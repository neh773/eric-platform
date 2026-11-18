package org.oip.redundancy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RedundancyMachineTest {
    @Test
    fun independentStaysActiveAndIgnoresTheLink() {
        val state = RedundancySettings(mode = NodeRole.INDEPENDENT).state()
        assertTrue(state.isActive)
        assertFalse(state.isMaster)
        val next = reduce(state, RedundancyEvent.HeartbeatMissed)
        assertEquals(ActivityLevel.ACTIVE, next.activity)
        assertFalse(next.connected)
        assertEquals(null, next.peer)
    }

    @Test
    fun masterAllowanceMakesItActiveBeforeAPeerExists() {
        val waiting = RedundancySettings(mode = NodeRole.MASTER, startupAllowanceMillis = 5_000).state()
        assertEquals(ActivityLevel.UNDECIDED, waiting.activity)
        val active = reduce(waiting, RedundancyEvent.AllowanceElapsed)
        assertEquals(ActivityLevel.ACTIVE, active.activity)
        assertTrue(active.becameActiveWithoutPeer)
        val again = reduce(active, RedundancyEvent.AllowanceElapsed)
        assertEquals(ActivityLevel.ACTIVE, again.activity)
    }

    @Test
    fun zeroAllowanceMasterDeactivatesAManualBackup() {
        val master = RedundancySettings(
            mode = NodeRole.MASTER,
            startupAllowanceMillis = 0,
            recoveryMode = RecoveryMode.MANUAL,
        ).state()
        assertTrue(master.becameActiveWithoutPeer)
        val next = reduce(master, RedundancyEvent.HeartbeatOk(peer(activity = ActivityLevel.ACTIVE, recovery = RecoveryMode.MANUAL)))
        assertEquals(ActivityLevel.ACTIVE, next.activity)
        assertEquals("deactivate", next.command)
        assertFalse(next.splitBrain)
    }

    @Test
    fun undecidedManualMasterYieldsToAnActiveBackup() {
        val master = RedundancySettings(
            mode = NodeRole.MASTER,
            startupAllowanceMillis = 30_000,
            recoveryMode = RecoveryMode.MANUAL,
        ).state()
        val yielded = reduce(master, RedundancyEvent.HeartbeatOk(peer(activity = ActivityLevel.ACTIVE, recovery = RecoveryMode.MANUAL)))
        assertEquals(ActivityLevel.WARM, yielded.activity)
        assertEquals("", yielded.command)
        assertFalse(yielded.becameActiveWithoutPeer)
        val still = reduce(yielded, RedundancyEvent.AllowanceElapsed)
        assertEquals(ActivityLevel.WARM, still.activity)
    }

    @Test
    fun automaticMasterTakesOverAndTellsTheBackupToStandDown() {
        val master = RedundancySettings(mode = NodeRole.MASTER, startupAllowanceMillis = 30_000).state()
        val next = reduce(master, RedundancyEvent.HeartbeatOk(peer(activity = ActivityLevel.ACTIVE)))
        assertEquals(ActivityLevel.ACTIVE, next.activity)
        assertEquals("deactivate", next.command)
        assertTrue(next.isMaster)
    }

    @Test
    fun backupStandsDownWhenTheMasterIsActiveAndRecoveryIsAutomatic() {
        val backup = RedundancySettings(mode = NodeRole.BACKUP, standbyActivityLevel = StandbyLevel.WARM).state()
        val next = reduce(backup, RedundancyEvent.HeartbeatOk(peer(role = NodeRole.MASTER, activity = ActivityLevel.ACTIVE)))
        assertEquals(ActivityLevel.WARM, next.activity)
        assertFalse(next.isActive)
        assertTrue(next.connected)
    }

    @Test
    fun backupStaysActiveWhileTheMasterIsStandingByInManualMode() {
        var backup = reduce(
            RedundancySettings(mode = NodeRole.BACKUP, standbyActivityLevel = StandbyLevel.COLD).state(),
            RedundancyEvent.AllowanceElapsed,
        )
        assertEquals(ActivityLevel.ACTIVE, backup.activity)
        backup = reduce(
            backup,
            RedundancyEvent.HeartbeatOk(peer(role = NodeRole.MASTER, activity = ActivityLevel.COLD, recovery = RecoveryMode.MANUAL)),
        )
        assertEquals(ActivityLevel.ACTIVE, backup.activity)
        assertFalse(backup.splitBrain)
    }

    @Test
    fun deactivateCommandStandsTheBackupDown() {
        val active = reduce(RedundancySettings(mode = NodeRole.BACKUP).state(), RedundancyEvent.AllowanceElapsed)
        val next = reduce(
            active,
            RedundancyEvent.HeartbeatOk(
                peer(role = NodeRole.MASTER, activity = ActivityLevel.ACTIVE, recovery = RecoveryMode.MANUAL, command = "deactivate"),
            ),
        )
        assertEquals(ActivityLevel.WARM, next.activity)
        assertFalse(next.splitBrain)
    }

    @Test
    fun missedHeartbeatsFailTheBackupOverAndLatchSplitBrain() {
        var backup = RedundancySettings(mode = NodeRole.BACKUP, pingMaxMissed = 3).state().copy(
            connected = true,
            activity = ActivityLevel.WARM,
            peer = peer(role = NodeRole.MASTER, activity = ActivityLevel.ACTIVE),
        )
        backup = reduce(backup, RedundancyEvent.HeartbeatMissed)
        backup = reduce(backup, RedundancyEvent.HeartbeatMissed)
        assertEquals(ActivityLevel.WARM, backup.activity)
        backup = reduce(backup, RedundancyEvent.HeartbeatMissed)
        assertEquals(ActivityLevel.ACTIVE, backup.activity)
        assertTrue(backup.splitBrain)
        assertFalse(backup.connected)
    }

    @Test
    fun theNextHeartbeatClearsSplitBrainOnceTheBackupYields() {
        val split = RedundancySettings(mode = NodeRole.BACKUP).state().copy(
            activity = ActivityLevel.ACTIVE,
            splitBrain = true,
            connected = false,
        )
        val healed = reduce(split, RedundancyEvent.HeartbeatOk(peer(role = NodeRole.MASTER, activity = ActivityLevel.ACTIVE)))
        assertEquals(ActivityLevel.WARM, healed.activity)
        assertFalse(healed.splitBrain)
    }

    @Test
    fun assumeControlIsMasterOnlyAndRequestsDeactivation() {
        val backup = reduce(RedundancySettings(mode = NodeRole.BACKUP).state(), RedundancyEvent.AssumeControl)
        assertEquals(ActivityLevel.UNDECIDED, backup.activity)
        val master = RedundancySettings(mode = NodeRole.MASTER, startupAllowanceMillis = 30_000, recoveryMode = RecoveryMode.MANUAL)
            .state()
            .copy(activity = ActivityLevel.WARM)
        val assumed = reduce(master, RedundancyEvent.AssumeControl)
        assertEquals(ActivityLevel.ACTIVE, assumed.activity)
        assertEquals("deactivate", assumed.command)
        assertFalse(assumed.yielding)
    }

    @Test
    fun requestFailoverYieldsUntilThePeerIsActiveThenAutomaticRecoveryTakesBack() {
        val master = RedundancySettings(mode = NodeRole.MASTER, startupAllowanceMillis = 0).state()
        val yielding = reduce(master, RedundancyEvent.RequestFailover)
        assertEquals(ActivityLevel.WARM, yielding.activity)
        assertEquals("activate", yielding.command)
        assertTrue(yielding.yielding)
        val waiting = reduce(yielding, RedundancyEvent.HeartbeatOk(peer(activity = ActivityLevel.WARM)))
        assertEquals(ActivityLevel.WARM, waiting.activity)
        assertEquals("activate", waiting.command)
        val back = reduce(waiting, RedundancyEvent.HeartbeatOk(peer(activity = ActivityLevel.ACTIVE)))
        assertEquals(ActivityLevel.ACTIVE, back.activity)
        assertEquals("deactivate", back.command)
        assertFalse(back.yielding)
    }

    @Test
    fun linkLossOnTheMasterLatchesSplitBrainWithoutClearingActive() {
        val master = RedundancySettings(mode = NodeRole.MASTER, startupAllowanceMillis = 0).state().copy(
            connected = true,
            peer = peer(activity = ActivityLevel.WARM),
        )
        val quiet = reduce(master, RedundancyEvent.LinkLost)
        assertEquals(ActivityLevel.ACTIVE, quiet.activity)
        assertFalse(quiet.splitBrain)
        val split = reduce(master.copy(peer = peer(activity = ActivityLevel.ACTIVE)), RedundancyEvent.LinkLost)
        assertEquals(ActivityLevel.ACTIVE, split.activity)
        assertTrue(split.splitBrain)
        assertFalse(split.connected)
    }

    @Test
    fun partialHistoryDropsSamplesTakenWhileTheMasterWasActive() {
        val open = listOf(ActiveWindow(1_000, 0))
        assertFalse(historyWhileMasterDown(1_000, open))
        assertFalse(historyWhileMasterDown(5_000, open))
        assertTrue(historyWhileMasterDown(999, open))
        val closed = listOf(ActiveWindow(1_000, 2_000))
        assertTrue(historyWhileMasterDown(1, closed))
        assertFalse(historyWhileMasterDown(1_000, closed))
        assertTrue(historyWhileMasterDown(2_000, closed))
    }

    @Test
    fun activeUptimeLetsTheLongerNodeKeepItsValue() {
        assertFalse(backupWinsConflict(100, 500, useActiveUptime = false))
        assertTrue(backupWinsConflict(100, 500, useActiveUptime = true))
        assertFalse(backupWinsConflict(500, 100, useActiveUptime = true))
        assertFalse(backupWinsConflict(100, 100, useActiveUptime = true))
    }

    @Test
    fun coldWarmAndActiveGatesMatchTheStandbyLevels() {
        assertFalse(RuntimeGate.forActivity(ActivityLevel.COLD).subscriptions)
        assertFalse(RuntimeGate.forActivity(ActivityLevel.UNDECIDED).deviceWrites)
        assertTrue(RuntimeGate.forActivity(ActivityLevel.WARM).subscriptions)
        assertFalse(RuntimeGate.forActivity(ActivityLevel.WARM).deviceWrites)
        assertFalse(RuntimeGate.forActivity(ActivityLevel.WARM).history)
        val active = RuntimeGate.forActivity(ActivityLevel.ACTIVE)
        assertTrue(active.subscriptions && active.deviceWrites && active.history)
    }

    @Test
    fun unknownRedundancyKeysAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RedundancySettings.parse(mapOf("mode" to "master", "nope" to true))
        }
    }

    private fun peer(
        role: NodeRole = NodeRole.BACKUP,
        activity: ActivityLevel = ActivityLevel.WARM,
        recovery: RecoveryMode = RecoveryMode.AUTOMATIC,
        command: String = "",
    ) = PeerSnapshot(
        role = role,
        activity = activity,
        activeUptimeMillis = 10,
        nodeId = "peer",
        configRevision = 1,
        runtimeRevision = 1,
        command = command,
        recovery = recovery,
        splitBrain = false,
    )
}
