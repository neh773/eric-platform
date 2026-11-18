package org.oip.modbus

/**
 * Modbus address syntax, aligned with the public the reference platform addressing page:
 *
 * Designators: HR/IR (int16), C/DI (coils and discrete inputs), HRUS/IRUS (uint16),
 * HRI/IRI (int32), HRUI/IRUI (uint32), HRI_64/IRI_64 (int64), HRUI_64/IRUI_64 (uint64),
 * HRF/IRF (float32), HRD/IRD (float64), HRBCD/IRBCD and HRBCD_32/IRBCD_32 (BCD),
 * HRS (string, `HRS<addr>:<even length>`).
 * A unit id may be prepended (`3.HR1024`). A bit inside a 16-bit register is `HR1024.0`
 * (bit 0 is the least significant bit; `HR1024.10` is the 11th bit).
 *
 * One-based addressing is the default: HR1 is protocol offset 0 (the traditional 40001).
 * Zero-based addressing uses the number as the protocol offset, so HR0 is offset 0.
 * The documented `IR0` example is the zero-based form.
 *
 * Function codes (public Modbus Application Protocol, and the reference platform function-code table):
 * https://modbus.org/docs/Modbus_Application_Protocol_V1_1b3.pdf
 */
class AddressException(message: String) : IllegalArgumentException(message)

enum class ModbusArea { COIL, DISCRETE_INPUT, HOLDING_REGISTER, INPUT_REGISTER }

enum class ModbusType {
    BOOLEAN, INT16, UINT16, INT32, UINT32, INT64, UINT64,
    FLOAT32, FLOAT64, STRING, BCD16, BCD32, BIT,
}

data class ParsedAddress(
    val raw: String,
    val unitId: Int,
    val area: ModbusArea,
    val type: ModbusType,
    /** First coil or register, protocol offset (0-based on the wire). */
    val protocolOffset: Int,
    /** Coils, or 16-bit registers, occupied by this item. */
    val span: Int,
    val bit: Int? = null,
    val stringLength: Int? = null,
) {
    val writable: Boolean get() = area == ModbusArea.COIL || area == ModbusArea.HOLDING_REGISTER
    val endExclusive: Int get() = protocolOffset + span

    /**
     * One listener is the gateway case. A list exists only when a second scan class shares the address.
     * Attach and detach run on API threads while the scan thread dispatches, so both fields are
     * volatile and the list is replaced, never changed in place.
     */
    @Volatile internal var rawOne: RawReg? = null
    @Volatile internal var rawMore: List<RawReg>? = null
    internal var lastGood: Any? = null
    internal var hasLast: Boolean = false

    @Synchronized
    internal fun addRaw(scanClass: String, sample: RawSample) {
        val reg = RawReg(scanClass, sample)
        val one = rawOne
        val more = rawMore
        if (one == null && more == null) {
            rawOne = reg
            return
        }
        val next = ArrayList<RawReg>((more?.size ?: 0) + 2)
        if (one != null) next.add(one)
        if (more != null) next.addAll(more)
        next.add(reg)
        rawMore = next
        rawOne = null
    }

    @Synchronized
    internal fun removeRaw(sample: RawSample) {
        if (rawOne?.sample === sample) {
            rawOne = null
            return
        }
        val more = rawMore ?: return
        rawMore = more.filter { it.sample !== sample }.ifEmpty { null }
    }

    internal fun hasRaw(scanClass: String): Boolean {
        val one = rawOne
        if (one != null) return one.scanClass == scanClass
        val more = rawMore ?: return false
        for (reg in more) if (reg.scanClass == scanClass) return true
        return false
    }

    internal fun dispatch(scanClass: String, value: Any?, quality: Quality, epochMillis: Long) {
        val one = rawOne
        if (one != null) {
            if (one.scanClass == scanClass) one.sample.onSample(value, quality, epochMillis)
            return
        }
        val more = rawMore ?: return
        for (reg in more) if (reg.scanClass == scanClass) reg.sample.onSample(value, quality, epochMillis)
    }
}

internal class RawReg(val scanClass: String, val sample: RawSample)

object ModbusAddress {
    private val HEAD = Regex("""^(?:(\d{1,3})\.)?([A-Za-z].+)$""")
    private val TAIL = Regex("""^(\d+)(?:\.(\d+))?(?::(\d+))?$""")

    private data class Spec(val area: ModbusArea, val type: ModbusType, val registers: Int)

    private val TABLE: Map<String, Spec> = listOf(
        "HRUI_64" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.UINT64, 4),
        "IRUI_64" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.UINT64, 4),
        "HRI_64" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.INT64, 4),
        "IRI_64" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.INT64, 4),
        "HRBCD_32" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.BCD32, 2),
        "IRBCD_32" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.BCD32, 2),
        "HRBCD" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.BCD16, 1),
        "IRBCD" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.BCD16, 1),
        "HRUS" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.UINT16, 1),
        "IRUS" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.UINT16, 1),
        "HRUI" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.UINT32, 2),
        "IRUI" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.UINT32, 2),
        "HRS" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.STRING, 0),
        "HRF" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.FLOAT32, 2),
        "IRF" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.FLOAT32, 2),
        "HRD" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.FLOAT64, 4),
        "IRD" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.FLOAT64, 4),
        "HRI" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.INT32, 2),
        "IRI" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.INT32, 2),
        "HR" to Spec(ModbusArea.HOLDING_REGISTER, ModbusType.INT16, 1),
        "IR" to Spec(ModbusArea.INPUT_REGISTER, ModbusType.INT16, 1),
        "DI" to Spec(ModbusArea.DISCRETE_INPUT, ModbusType.BOOLEAN, 1),
        "C" to Spec(ModbusArea.COIL, ModbusType.BOOLEAN, 1),
    ).toMap()

    private val DESIGNATORS = TABLE.keys.sortedByDescending { it.length }

    fun parse(raw: String, config: ModbusDeviceConfig): ParsedAddress {
        val text = raw.trim()
        val head = HEAD.matchEntire(text) ?: throw AddressException("invalid address '$raw'")
        val unit = head.groupValues[1].ifEmpty { null }?.toInt() ?: config.unitId
        if (unit !in 0..255) throw AddressException("unit id $unit is outside 0..255")
        val rest = head.groupValues[2]
        val designator = DESIGNATORS.firstOrNull { rest.startsWith(it, ignoreCase = true) }
            ?: throw AddressException("unknown designator in '$raw'")
        val spec = TABLE.getValue(designator)
        val tail = TAIL.matchEntire(rest.substring(designator.length))
            ?: throw AddressException("invalid address '$raw'")
        val number = tail.groupValues[1].toInt()
        val bit = tail.groupValues[2].ifEmpty { null }?.toInt()
        val strlen = tail.groupValues[3].ifEmpty { null }?.toInt()

        val offset = if (config.zeroBasedAddressing) number else number - 1
        if (offset < 0 || offset > 65535) throw AddressException("address $number is out of range")

        if (spec.type == ModbusType.STRING) {
            if (bit != null) throw AddressException("bit addressing is not valid on a string")
            if (strlen == null || strlen <= 0 || strlen % 2 != 0)
                throw AddressException("string length must be a positive even number of characters (two per register)")
            if (strlen > 250) throw AddressException("string length $strlen exceeds 250 characters")
            checkSpan(offset, strlen / 2)
            return ParsedAddress(text, unit, spec.area, ModbusType.STRING, offset, strlen / 2, stringLength = strlen)
        }
        if (strlen != null) throw AddressException("only string addresses take a :length suffix")

        if (bit != null) {
            if (spec.type != ModbusType.INT16 && spec.type != ModbusType.UINT16)
                throw AddressException("bit addressing is only valid on a 16-bit register")
            if (bit !in 0..15) throw AddressException("bit $bit is outside 0..15")
            checkSpan(offset, 1)
            return ParsedAddress(text, unit, spec.area, ModbusType.BIT, offset, 1, bit = bit)
        }
        checkSpan(offset, spec.registers)
        return ParsedAddress(text, unit, spec.area, spec.type, offset, spec.registers)
    }

    private fun checkSpan(offset: Int, span: Int) {
        if (span < 1 || offset.toLong() + span - 1 > 65535)
            throw AddressException("address span exceeds register 65535")
    }
}
