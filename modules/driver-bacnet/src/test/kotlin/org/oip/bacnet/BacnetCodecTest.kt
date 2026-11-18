package org.oip.bacnet

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Byte vectors worked out by hand from the public encoding rules of the BACnet standard (clause 20.2 tagged
 * values, clause 20.1 APDU headers, clause 6 network layer, Annex J BVLC), not captured from any product.
 * doc source: FEATURES DEV-011, DEV-012, DEV-038
 */
class BacnetCodecTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun enc(block: TagWriter.() -> Unit): ByteArray = TagWriter().apply(block).bytes()
    private fun dec(s: String): Any? = TagReader(hex(s)).appValue()

    @Test fun applicationTagsEncodeAsTheStandardDescribes() {
        assertArrayEquals(hex("00"), enc { appNull() })
        assertArrayEquals(hex("11"), enc { appBoolean(true) })
        assertArrayEquals(hex("10"), enc { appBoolean(false) })
        assertArrayEquals(hex("21 48"), enc { appUnsigned(72) })
        assertArrayEquals(hex("22 01 00"), enc { appUnsigned(256) })
        assertArrayEquals(hex("24 FF FF FF FF"), enc { appUnsigned(0xFFFFFFFFL) })
        assertArrayEquals(hex("31 48"), enc { appSigned(72) })
        assertArrayEquals(hex("31 FF"), enc { appSigned(-1) })
        assertArrayEquals(hex("32 FF 7F"), enc { appSigned(-129) })
        assertArrayEquals(hex("44 42 91 00 00"), enc { appReal(72.5f) })
        assertArrayEquals(hex("55 08 3F F0 00 00 00 00 00 00"), enc { appDouble(1.0) })
        assertArrayEquals(hex("63 01 02 03"), enc { appOctets(byteArrayOf(1, 2, 3)) })
        assertArrayEquals(hex("74 00 61 62 63"), enc { appString("abc") })
        // More than four content bytes: length value 5 and one extended length byte.
        assertArrayEquals(hex("75 0B 00 46 61 6E 20 53 74 61 74 75 73"), enc { appString("Fan Status") })
        // Status flags {in-alarm F, fault T, overridden F, out-of-service F}: 4 unused bits.
        assertArrayEquals(hex("82 04 40"), enc { appBitString(listOf(false, true, false, false)) })
        assertArrayEquals(hex("91 01"), enc { appEnumerated(1) })
        // analog-input 5: type 0 in the top 10 bits, instance 5 in the low 22.
        assertArrayEquals(hex("C4 00 00 00 05"), enc { appObjectId(ObjectId(0, 5)) })
        // device 260001 = (8 << 22) | 0x03F7A1.
        assertArrayEquals(hex("C4 02 03 F7 A1"), enc { appObjectId(ObjectId(8, 260001)) })
    }

    @Test fun contextTagsOpeningClosingAndExtendedNumbers() {
        assertArrayEquals(hex("09 55"), enc { ctxEnumerated(0, 85) })
        assertArrayEquals(hex("19 55"), enc { ctxEnumerated(1, 85) })
        assertArrayEquals(hex("49 08"), enc { ctxUnsigned(4, 8) })
        assertArrayEquals(hex("0C 00 C0 00 01"), enc { ctxObjectId(0, ObjectId(3, 1)) })
        assertArrayEquals(hex("3E 3F"), enc { open(3); close(3) })
        // Tag numbers above 14 put 15 in the tag field and the number in the next byte.
        assertArrayEquals(hex("F9 14 01"), enc { ctxUnsigned(20, 1) })
        assertArrayEquals(hex("FE 14 FF 14"), enc { open(20); close(20) })

        val r = TagReader(hex("F9 14 01 FE 14 FF 14 19 55"))
        val h = r.peek()!!
        assertTrue(h.context); assertEquals(20, h.number)
        assertEquals(1L, r.ctxUnsigned(20))
        assertTrue(r.isOpen(20)); r.expectOpen(20); r.expectClose(20)
        assertTrue(r.isContext(1)); assertEquals(85L, r.ctxUnsigned(1))
        assertTrue(r.atEnd())
    }

    @Test fun applicationTagsDecode() {
        assertEquals(BacnetNull, dec("00"))
        assertEquals(true, dec("11"))
        assertEquals(false, dec("10"))
        assertEquals(BacnetUnsigned(256), dec("22 01 00"))
        assertEquals(-129L, dec("32 FF 7F"))
        assertEquals(72.5f, dec("44 42 91 00 00"))
        assertEquals(1.0, dec("55 08 3F F0 00 00 00 00 00 00"))
        assertEquals("abc", dec("74 00 61 62 63"))
        assertEquals("Fan Status", dec("75 0B 00 46 61 6E 20 53 74 61 74 75 73"))
        // ISO 8859-1 character set (5) and UCS-2 (4).
        assertEquals("é", dec("72 05 E9"))
        assertEquals("A", dec("73 04 00 41"))
        assertEquals(BacnetBitString(listOf(false, true, false, false)), dec("82 04 40"))
        assertEquals(BacnetEnumerated(1), dec("91 01"))
        assertEquals("2024-03-*", dec("A4 7C 03 FF FF"))
        assertEquals("12:30:00.00", dec("B4 0C 1E 00 00"))
        assertEquals(ObjectId(8, 260001), dec("C4 02 03 F7 A1"))
        // A two-byte extended length (254) holds a 300 byte octet string.
        val long = TagReader(hex("65 FE 01 2C") + ByteArray(300)).appValue() as ByteArray
        assertEquals(300, long.size)
        assertThrows(BacnetDecodeException::class.java) { dec("44 42 91") }
        assertThrows(BacnetDecodeException::class.java) { dec("C3 00 00 00") }
    }

    @Test fun confirmedReadPropertyRequestInAUnicastFrame() {
        // Confirmed request (type 0), max segments unspecified, max APDU 1476 (code 5), invoke id 1,
        // ReadProperty (12) of analog-input 5 present-value (85).
        val apdu = BacnetFrames.confirmed(1, BacnetService.READ_PROPERTY, BacnetFrames.readProperty(ObjectId(0, 5), 85))
        assertArrayEquals(hex("00 05 01 0C 0C 00 00 00 05 19 55"), apdu)
        // BVLC original-unicast, 4 + NPDU (version 1, control 0x04 expecting reply) + APDU.
        val frame = BacnetFrames.frame(apdu, null, broadcast = false, expectingReply = true)
        assertArrayEquals(hex("81 0A 00 11 01 04 00 05 01 0C 0C 00 00 00 05 19 55"), frame)
        assertArrayEquals(hex("0C 00 00 00 4C 19 4C 29 03"), BacnetFrames.readProperty(ObjectId(0, 76), 76, 3))
        assertEquals(0, BacnetFrames.maxApduCode(50))
        assertEquals(3, BacnetFrames.maxApduCode(480))
        assertEquals(5, BacnetFrames.maxApduCode(1476))
        assertEquals(1024, BacnetFrames.maxApduSize(4))
    }

    @Test fun whoIsAndIAm() {
        assertArrayEquals(hex("10 08"), BacnetFrames.unconfirmed(BacnetService.WHO_IS, BacnetFrames.whoIs(null, null)))
        assertArrayEquals(hex("10 08 09 03 19 03"), BacnetFrames.unconfirmed(BacnetService.WHO_IS, BacnetFrames.whoIs(3, 3)))
        assertArrayEquals(hex("0A 01 00 1A 01 00"), BacnetFrames.whoIs(256, 256))
        val broadcast = BacnetFrames.frame(hex("10 08"), null, broadcast = true, expectingReply = false)
        assertArrayEquals(hex("81 0B 00 08 01 00 10 08"), broadcast)
        val global = BacnetFrames.frame(hex("10 08"), null, broadcast = true, expectingReply = false, globalBroadcast = true)
        // DNET 0xFFFF, DLEN 0 (all MACs), hop count 255.
        assertArrayEquals(hex("81 0B 00 0C 01 20 FF FF 00 FF 10 08"), global)

        assertEquals(WhoIs(null, null), WhoIs.parse(ByteArray(0)))
        assertEquals(WhoIs(3, 3), WhoIs.parse(hex("09 03 19 03")))
        assertTrue(WhoIs(1, 5).matches(3)); assertTrue(!WhoIs(1, 2).matches(3)); assertTrue(WhoIs(null, null).matches(9))

        // I-Am device 260001, max APDU 1476, segmentation none (3), vendor 260.
        val iAm = hex("C4 02 03 F7 A1 22 05 C4 91 03 22 01 04")
        assertArrayEquals(iAm, BacnetFrames.iAm(260001, 1476, 3, 260))
        assertEquals(IAm(260001, 1476, 3, 260), IAm.parse(iAm))
        assertThrows(BacnetDecodeException::class.java) { IAm.parse(hex("C4 00 00 00 05 22 05 C4 91 03 22 01 04")) }
    }

    @Test fun writePropertyWithAndWithoutPriority() {
        // analog-output 1 present-value := 50.0 at priority 8.
        assertArrayEquals(
            hex("0C 00 40 00 01 19 55 3E 44 42 48 00 00 3F 49 08"),
            BacnetFrames.writeProperty(ObjectId(1, 1), 85, null, 50.0f, 8),
        )
        // Relinquish (NULL) at priority 16.
        assertArrayEquals(hex("0C 00 40 00 01 19 55 3E 00 3F 49 10"), BacnetFrames.writeProperty(ObjectId(1, 1), 85, null, BacnetNull, 16))
        // binary-value 2 out-of-service := TRUE, no priority.
        assertArrayEquals(hex("0C 01 40 00 02 19 51 3E 11 3F"), BacnetFrames.writeProperty(ObjectId(5, 2), 81, null, true, null))
    }

    @Test fun timeSynchronizationCarriesADateAndATime() {
        // 2026-11-10 is a Tuesday (day of week 2). Date: tag 10, year - 1900 = 126; time: tag 11, hundredths last.
        val at = java.time.LocalDateTime.of(2026, 11, 10, 13, 45, 30, 120_000_000)
        assertArrayEquals(hex("A4 7E 0B 0A 02 B4 0D 2D 1E 0C"), BacnetFrames.timeSynchronization(at))
        assertArrayEquals(hex("10 06 A4 7E 0B 0A 02 B4 0D 2D 1E 0C"), BacnetFrames.unconfirmed(BacnetService.TIME_SYNCHRONIZATION, BacnetFrames.timeSynchronization(at)))
        assertArrayEquals(hex("10 09"), BacnetFrames.unconfirmed(BacnetService.UTC_TIME_SYNCHRONIZATION, ByteArray(0)))
        // The decoder reads the same date and time back.
        val r = TagReader(BacnetFrames.timeSynchronization(at))
        assertEquals("2026-11-10", r.appValue())
        assertEquals("13:45:30.12", r.appValue())
    }

    @Test fun readPropertyMultipleRequestAndAck() {
        val req = BacnetFrames.readPropertyMultiple(listOf(
            ObjectId(0, 16) to listOf(PropertyRef(85), PropertyRef(111)),
            ObjectId(8, 1) to listOf(PropertyRef(76, 0)),
        ))
        assertArrayEquals(hex("0C 00 00 00 10 1E 09 55 09 6F 1F 0C 02 00 00 01 1E 09 4C 19 00 1F"), req)

        // analog-input 16: present-value 72.3, status flags all clear, unknown-property for 999 (class 2 code 32).
        val ack = hex("0C 00 00 00 10 1E 29 55 4E 44 42 90 99 9A 4F 29 6F 4E 82 04 00 4F 2A 03 E7 5E 91 02 91 20 5F 1F")
        val results = BacnetAcks.readPropertyMultiple(ack)
        assertEquals(3, results.size)
        assertEquals(ObjectId(0, 16), results[0].obj)
        assertEquals(85, results[0].property)
        assertEquals(72.3f, results[0].value)
        assertEquals(BacnetBitString(listOf(false, false, false, false)), results[1].value)
        assertEquals(999, results[2].property)
        assertEquals(2L, results[2].errorClass); assertEquals(32L, results[2].errorCode)
        assertTrue(!results[2].ok)
    }

    @Test fun readPropertyAckWithAnArrayValue() {
        // device 1 object-list = {device 1, analog-input 0}
        val ack = hex("0C 02 00 00 01 19 4C 3E C4 02 00 00 01 C4 00 00 00 00 3F")
        val r = BacnetAcks.readProperty(ack)
        assertEquals(76, r.property)
        assertNull(r.index)
        assertEquals(listOf(ObjectId(8, 1), ObjectId(0, 0)), r.value)
        val one = BacnetAcks.readProperty(hex("0C 02 00 00 01 19 4C 29 00 3E 21 02 3F"))
        assertEquals(0, one.index)
        assertEquals(BacnetUnsigned(2), one.value)
    }

    @Test fun apduHeadersParse() {
        fun apdu(s: String) = hex(s).let { BacnetFrames.parseApdu(it, 0, it.size) }
        assertEquals(Apdu.SimpleAck(7, 15), apdu("20 07 0F"))
        val complex = apdu("30 07 0C 0C 00 00 00 05 19 55 3E 44 42 91 00 00 3F") as Apdu.ComplexAck
        assertEquals(7, complex.invokeId); assertEquals(12, complex.service); assertTrue(!complex.segmented)
        assertEquals(72.5f, BacnetAcks.readProperty(complex.data).value)
        // Segmented complex ack: sequence number and proposed window size follow the invoke id.
        val seg = apdu("38 07 00 04 0C 0C") as Apdu.ComplexAck
        assertTrue(seg.segmented); assertEquals(12, seg.service)
        assertEquals(Apdu.Error(7, 12, 2, 32), apdu("50 07 0C 91 02 91 20"))
        assertEquals(Apdu.Reject(7, 9), apdu("60 07 09"))
        assertEquals(Apdu.Abort(7, 4, true), apdu("71 07 04"))
        assertEquals(Apdu.Abort(7, 4, false), apdu("70 07 04"))
        val req = apdu("00 05 09 0C 0C 00 00 00 05 19 55") as Apdu.ConfirmedRequest
        assertEquals(9, req.invokeId); assertEquals(1476, req.maxApdu); assertEquals(12, req.service)
        assertArrayEquals(hex("50 07 0C 91 02 91 20"), BacnetFrames.error(7, 12, 2, 32))
        assertThrows(BacnetDecodeException::class.java) { apdu("20 07") }
    }

    @Test fun networkLayerRoutingFields() {
        val from = InetSocketAddress(InetAddress.getLoopbackAddress(), 47808)
        // A reply routed from network 5, MAC 0x0A: control 0x08 (SNET present), SNET 0005, SLEN 1, SADR 0A.
        val routed = hex("81 0A 00 0D 01 08 00 05 01 0A 20 07 0F")
        val r = BacnetFrames.parse(routed, routed.size, from)!!
        assertEquals(5, r.source.network)
        assertArrayEquals(hex("0A"), r.source.mac)
        assertEquals(Apdu.SimpleAck(7, 15), r.apdu)
        // A request to that device carries DNET 5, DLEN 1, DADR 0A and hop count 255.
        val out = BacnetFrames.frame(hex("00 05 01 0C"), BacnetAddress(from, 5, hex("0A")), broadcast = false, expectingReply = true)
        assertArrayEquals(hex("81 0A 00 0F 01 24 00 05 01 0A FF 00 05 01 0C"), out)
        // Forwarded-NPDU from a BBMD names the original source 192.168.1.20:47808.
        val fwd = hex("81 04 00 0E C0 A8 01 14 BA C0 01 00 10 08")
        val f = BacnetFrames.parse(fwd, fwd.size, from)!!
        assertEquals(InetSocketAddress(InetAddress.getByAddress(hex("C0 A8 01 14")), 47808), f.source.socket)
        assertTrue(f.broadcast)
        // A network layer message (control bit 7) and a BVLC-Result carry no APDU.
        assertNull(BacnetFrames.parse(hex("81 0A 00 08 01 80 01 00"), 8, from))
        assertNull(BacnetFrames.parse(hex("81 00 00 06 00 00"), 6, from))
        assertThrows(BacnetDecodeException::class.java) { BacnetFrames.parse(hex("81 0A 00 09 01 00 20 07 0F"), 8, from) }
    }

    @Test fun itemAddresses() {
        val pv = BacnetItemAddress.parse("analog-output:1/PresentValue;priority=8")
        assertEquals(ObjectId(1, 1), pv.obj); assertEquals(85, pv.property); assertEquals(8, pv.priority)
        assertEquals("analog-output:1/PresentValue;priority=8", pv.toString())
        assertEquals(3, BacnetItemAddress.parse("binary-input:2/StatusFlags/OutOfService").item!!.bit)
        assertEquals(1, BacnetItemAddress.parse("analog-input:0/statusflags/fault").item!!.bit)
        assertEquals(103, BacnetItemAddress.parse("object-type-0:7/103").property)
        assertTrue(BacnetItemAddress.parse("device:9").isObject)
        for (bad in listOf("analog-input", "nope:1/PresentValue", "analog-input:x", "analog-input:1/Nope",
                "analog-input:1;priority=3", "analog-output:1/PresentValue;priority=17", "analog-output:1/Units;priority=2",
                "analog-input:4194304/PresentValue", "analog-input:1/PresentValue;speed=2")) {
            assertThrows(AddressException::class.java, { BacnetItemAddress.parse(bad) }, bad)
        }
        assertEquals(true, BacnetValues.forItem(BacnetObjectType.BINARY_INPUT, BacnetItem.PRESENT_VALUE, BacnetEnumerated(1)))
        assertEquals(72.5, BacnetValues.forItem(BacnetObjectType.ANALOG_INPUT, BacnetItem.PRESENT_VALUE, 72.5f))
        assertEquals(true, BacnetValues.forItem(0, BacnetItem.FAULT, BacnetBitString(listOf(false, true, false, false))))
        assertEquals(50f, BacnetValues.forWrite(BacnetObjectType.ANALOG_OUTPUT, BacnetItem.PRESENT_VALUE, 50))
        assertEquals(BacnetEnumerated(1), BacnetValues.forWrite(BacnetObjectType.BINARY_OUTPUT, BacnetItem.PRESENT_VALUE, "on"))
        assertEquals(BacnetUnsigned(3), BacnetValues.forWrite(BacnetObjectType.MULTI_STATE_OUTPUT, BacnetItem.PRESENT_VALUE, 3))
        assertEquals(BacnetNull, BacnetValues.forWrite(BacnetObjectType.ANALOG_OUTPUT, BacnetItem.PRESENT_VALUE, null))
        assertThrows(OutOfRangeException::class.java) { BacnetValues.forWrite(BacnetObjectType.MULTI_STATE_OUTPUT, BacnetItem.PRESENT_VALUE, 0) }
        assertThrows(TypeMismatchException::class.java) { BacnetValues.forWrite(BacnetObjectType.ANALOG_INPUT, BacnetItem.PRESENT_VALUE, null) }
        assertThrows(TypeMismatchException::class.java) { BacnetValues.forWrite(BacnetObjectType.ANALOG_OUTPUT, BacnetItem.PRESENT_VALUE, "x") }
    }
}
