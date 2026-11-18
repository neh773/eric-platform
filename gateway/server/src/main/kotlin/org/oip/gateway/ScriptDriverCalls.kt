package org.oip.gateway

import org.oip.bacnet.BacnetDriverModule
import org.oip.bacnet.BacnetObjectType
import org.oip.bacnet.BacnetRemoteDevice
import org.oip.bacnet.ObjectId
import org.oip.dnp3.CommandMode
import org.oip.dnp3.CommandResult
import org.oip.dnp3.Crob
import org.oip.dnp3.Dnp3DriverModule
import org.oip.dnp3.Dnp3Outstation

/**
 * `system.bacnet` (doc source: FEATURES SCR-007): raw property reads and writes and time synchronization on a
 * configured BACnet/IP remote device. `system.dnp` (doc source: FEATURES SCR-003, DEV-013): control relay output
 * and analog output commands to a DNP3 outstation, by select-before-operate or direct operate. [org.oip.scripting.ScriptHost.libraryCall] has already decided who may
 * call each function (gateway scripts only); this runs them on the driver.
 */
internal object ScriptDriverCalls {
    fun handles(function: String): Boolean = function.startsWith("bacnet.") || function.startsWith("dnp.")

    fun call(gw: Gateway, function: String, args: List<Any?>): Any? = when (function) {
        "bacnet.readRaw" -> bacnet(gw, args).readRaw(objectId(args), int(args, 3, "propertyId", 0..4_194_303), optInt(args, 4, "propertyArrayIndex"))
        "bacnet.writeRaw" -> bacnet(gw, args).writeRaw(
            objectId(args), int(args, 3, "propertyId", 0..4_194_303), args.getOrNull(4),
            priority = optInt(args, 5, "priority"), index = optInt(args, 6, "propertyArrayIndex"),
        ).let { quality(it.code.name, it.diagnostic) }
        "bacnet.synchronizeTime" -> bacnet(gw, args).synchronizeTime(utc = false)
        "bacnet.synchronizeTimeUtc" -> bacnet(gw, args).synchronizeTime(utc = true)
        "dnp.selectOperateBinary" -> binary(gw, args, CommandMode.SELECT_BEFORE_OPERATE)
        "dnp.directOperateBinary" -> binary(gw, args, CommandMode.DIRECT_OPERATE)
        "dnp.selectOperateAnalog" -> analog(gw, args, CommandMode.SELECT_BEFORE_OPERATE)
        "dnp.directOperateAnalog" -> analog(gw, args, CommandMode.DIRECT_OPERATE)
        else -> throw IllegalArgumentException("unknown function")
    }

    private fun outstation(gw: Gateway, args: List<Any?>): Dnp3Outstation {
        val name = name(args, "outstation")
        val module = gw.host.instance(Dnp3DriverModule.MODULE_ID) as? Dnp3DriverModule
            ?: throw IllegalStateException("the DNP3 driver is not running")
        return module.device(name) ?: throw IllegalArgumentException("DNP3 outstation '$name' is not configured")
    }

    /** Arguments: outstation, index, trip/close code (NUL, CLOSE, TRIP or 0..2), operation (NUL, PULSE_ON, PULSE_OFF, LATCH_ON, LATCH_OFF or 0..4), count, on ms, off ms. */
    private fun binary(gw: Gateway, args: List<Any?>, mode: CommandMode): Map<String, Any?> {
        val device = outstation(gw, args)
        val index = int(args, 1, "index", 0..65535)
        val tcc = code(args.getOrNull(2), "tcc", listOf("NUL", "CLOSE", "TRIP"))
        val op = code(args.getOrNull(3), "opType", listOf("NUL", "PULSE_ON", "PULSE_OFF", "LATCH_ON", "LATCH_OFF"))
        val count = if (args.getOrNull(4) == null) 1 else int(args, 4, "count", 0..255)
        val on = if (args.getOrNull(5) == null) 0L else int(args, 5, "onTime", 0..Int.MAX_VALUE).toLong()
        val off = if (args.getOrNull(6) == null) 0L else int(args, 6, "offTime", 0..Int.MAX_VALUE).toLong()
        return result(device.operateBinary(index, Crob((tcc shl 6) or op, count, on, off), mode))
    }

    /** Arguments: outstation, index, value, variation (1 32-bit, 2 16-bit, 3 float, 4 double; none picks 32-bit or float). */
    private fun analog(gw: Gateway, args: List<Any?>, mode: CommandMode): Map<String, Any?> {
        val device = outstation(gw, args)
        val index = int(args, 1, "index", 0..65535)
        val value = args.getOrNull(2) as? Number ?: throw IllegalArgumentException("value must be a number")
        val variation = if (args.getOrNull(3) == null) device.config.analogOutputVariation else int(args, 3, "variation", 1..4)
        return result(device.operateAnalog(index, value, variation, mode))
    }

    private fun code(raw: Any?, what: String, names: List<String>): Int = when (raw) {
        is Number -> raw.toInt().takeIf { raw.toDouble() == it.toDouble() && it in names.indices }
        is String -> names.indexOf(raw.trim().uppercase().replace('-', '_')).takeIf { it >= 0 }
        else -> null
    } ?: throw IllegalArgumentException("$what must be one of ${names.joinToString()} or 0..${names.size - 1}")

    private fun result(r: CommandResult): Map<String, Any?> =
        linkedMapOf("ok" to r.ok, "status" to r.status, "statusName" to r.statusName, "diagnostic" to r.diagnostic)

    private fun quality(code: String, diagnostic: String?): Map<String, Any?> =
        linkedMapOf("quality" to code, "good" to (code == "Good"), "diagnostic" to diagnostic)

    private fun name(args: List<Any?>, what: String): String {
        val value = args.getOrNull(0) as? String
        if (value.isNullOrBlank() || value.length > 200 || value.any { it.code < 0x20 }) throw IllegalArgumentException("$what name is invalid")
        return value
    }

    private fun bacnet(gw: Gateway, args: List<Any?>): BacnetRemoteDevice {
        val name = name(args, "device")
        val module = gw.host.instance(BacnetDriverModule.MODULE_ID) as? BacnetDriverModule
            ?: throw IllegalStateException("the BACnet driver is not running")
        return module.device(name) ?: throw IllegalArgumentException("BACnet device '$name' is not configured")
    }

    /** Object type as its number or its name (`analog-input`), then the instance. */
    private fun objectId(args: List<Any?>): ObjectId {
        val type = when (val raw = args.getOrNull(1)) {
            is Number -> raw.toInt().takeIf { raw.toDouble() == it.toDouble() && it in 0..1023 }
            is String -> BacnetObjectType.parse(raw)
            else -> null
        } ?: throw IllegalArgumentException("objectType must be an object type number or name")
        return ObjectId(type, int(args, 2, "objectInstance", 0..4_194_303))
    }

    private fun int(args: List<Any?>, index: Int, what: String, range: IntRange): Int {
        val raw = args.getOrNull(index) as? Number ?: throw IllegalArgumentException("$what must be a whole number")
        val v = raw.toLong()
        if (raw.toDouble() != v.toDouble() || v !in range.first.toLong()..range.last.toLong()) throw IllegalArgumentException("$what is out of range")
        return v.toInt()
    }

    private fun optInt(args: List<Any?>, index: Int, what: String): Int? =
        if (args.getOrNull(index) == null) null else int(args, index, what, 0..Int.MAX_VALUE)
}
