package org.oip.simulator

/**
 * OPC UA status word. Good is 0. An address that is not in the program is 0x803E0000
 * (Bad_NotFound on this platform). A value the address type cannot hold is 0x80740000
 * (OPC UA Bad_TypeMismatch). A [readonly] address is 0x803B0000 (Bad_NotWritable).
 * `qv` uses the severity words from the programmable simulator page: Good (0),
 * Uncertain (0x40000000), and Bad (0x80000000).
 *
 * https://reference.opcfoundation.org/Core/Part6/v105/docs/A.2
 */
class Quality(val statusCode: Long, val diagnostic: String? = null) {
    companion object {
        val GOOD: Quality = Quality(0)
        val UNCERTAIN: Quality = Quality(0x40000000, "Uncertain")
        val BAD: Quality = Quality(0x80000000, "Bad")
        val NOT_FOUND: Quality = Quality(0x803E0000, "address is not in the program")
        val NOT_WRITABLE: Quality = Quality(0x803B0000, "address is read only")

        fun typeMismatch(detail: String): Quality = Quality(0x80740000, detail)
    }
}

class Sample(val value: Any?, val quality: Quality, val epochMillis: Long)

class WriteResult(val address: String, val quality: Quality)
