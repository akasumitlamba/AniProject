package app.anihome.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class OperationRevisionTest {
    @Test fun limitedScanBroadcastWinsOverLateBinderReply() {
        val scan = OperationRevision()
        val request = scan.snapshot()
        scan.invalidate() // Android has delivered a limited-results outcome.
        assertFalse(scan.isCurrent(request))
        assertTrue(scan.isCurrent(scan.snapshot()))
    }
    @Test fun radioLossInvalidatesAcceptedConnectionEvenAfterReconnect() {
        val connection = OperationRevision()
        val old = connection.invalidate()
        connection.invalidate() // Radio/profile service lost.
        val retry = connection.invalidate()
        assertFalse(connection.isCurrent(old))
        assertTrue(connection.isCurrent(retry))
    }
    @Test fun multipleInvalidationsNeverReviveAnOldReply() {
        val state = OperationRevision()
        val initial = state.snapshot()
        repeat(10) { state.invalidate() }
        assertFalse(state.isCurrent(initial))
    }
}
