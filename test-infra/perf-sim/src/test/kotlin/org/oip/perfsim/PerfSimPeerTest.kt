package org.oip.perfsim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Driver entry for PRF-012 refuses a loopback peer before any socket is opened.
 * Runs in the build, with no container. The simulators job runs the same check
 * again from the Docker harness.
 */
class PerfSimPeerTest {
    @Test fun driverRefusesLoopbackBeforeConnecting() {
        PerfSimPeer.assertRefusesLoopback()
    }

    @Test fun aBridgeAddressIsAccepted() {
        assertEquals("172.30.0.2", PerfSimPeer.require("172.30.0.2"))
    }
}
