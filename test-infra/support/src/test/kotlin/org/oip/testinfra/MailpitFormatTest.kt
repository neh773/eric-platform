package org.oip.testinfra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MailpitFormatTest {
    @Test fun dotStuffsBodyLines() {
        assertEquals("hello\r\n..secret\r\nline", dotStuff("hello\n.secret\nline"))
        assertEquals("already\r\n..stuffed", dotStuff("already\r\n.stuffed"))
    }
}
