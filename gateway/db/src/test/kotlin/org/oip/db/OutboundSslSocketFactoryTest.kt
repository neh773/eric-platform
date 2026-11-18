package org.oip.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** SEC-022: which PostgreSQL connections take the gateway's outbound trust. Doc source: FEATURES SEC-022. */
class OutboundSslSocketFactoryTest {
    private val ours = OutboundSslSocketFactory::class.java.name

    @Test fun onlyAVerifiedTlsConnectionWithoutItsOwnFactoryGetsTheStore() {
        assertEquals(ours, OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db:5432/plant?sslmode=verify-full", emptyMap()))
        assertEquals(ours, OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db/plant?sslmode=require", emptyMap()))
        assertEquals(ours, OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db/plant", mapOf("sslmode" to "verify-ca")))
        assertEquals(ours, OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db/plant?ssl=true", emptyMap()))
        // Plain text, or modes that fall back to plain text when TLS fails, keep the driver's behaviour.
        assertNull(OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db/plant", emptyMap()))
        assertNull(OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db/plant?sslmode=prefer", emptyMap()))
        assertNull(OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db/plant?ssl=true&sslmode=disable", emptyMap()))
        // An explicit factory, in the url or the extra properties, is the administrator's choice.
        assertNull(OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db/plant?sslmode=verify-full&sslfactory=x.Y", emptyMap()))
        assertNull(OutboundSslSocketFactory.forPostgres("jdbc:postgresql://db/plant?sslmode=verify-full", mapOf("sslFactory" to "x.Y")))
    }
}
