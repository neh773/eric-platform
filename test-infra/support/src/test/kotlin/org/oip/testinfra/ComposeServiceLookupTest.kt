package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComposeServiceLookupTest {
    @Test fun ambassadorPortResolvesToTheComposeService() {
        val listed = """
            4b9bffbde5f0	0.0.0.0:32771->2000/tcp, [::]:32771->2000/tcp	
            301fc2ab8472	0.0.0.0:5020->5020/tcp, [::]:5020->5020/tcp	proj	toxiproxy
            ad20ff9dccb2	0.0.0.0:15020->5020/tcp, 0.0.0.0:15021->8080/tcp	proj	modbus
            3138b526e5bf	0.0.0.0:14841->8080/tcp	proj	opcua
        """.trimIndent() + "\n"
        assertEquals("ad20ff9dccb2", composeServiceIdFromListing(listed, "modbus", 32771))
        assertEquals("3138b526e5bf", composeServiceIdFromListing(listed, "opcua", 14841))
    }

    @Test fun directPublishUsesThatContainer() {
        val listed = "ad20ff9dccb2\t0.0.0.0:15021->8080/tcp\tproj\tmodbus\n"
        assertEquals("ad20ff9dccb2", composeServiceIdFromListing(listed, "modbus", 15021))
    }
}
