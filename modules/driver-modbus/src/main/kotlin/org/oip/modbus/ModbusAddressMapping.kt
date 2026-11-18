package org.oip.modbus

/**
 * Modbus address mapping: one range row expands into many named items, each with a full driver address.
 *
 * Fields follow the public address-mapping page:
 *
 * - [prefix] goes before each generated number. Letters, digits, and underscores. It may not be a bare
 *   designator name (HR, IR, C, DI), so a generated name is never mistaken for an address.
 * - [start] and [end] are written in [radix] and are inclusive: Temp1..Temp10 is ten items.
 *   Both are 0..65535.
 * - [radix] is the base used to count and print the numbers (2..36; 8, 10, and 16 are the usual ones).
 * - [step]: when on, consecutive items advance by the type's register width (two registers for a 32-bit
 *   type, four for a 64-bit type) so wide values do not overlap. When off, each item advances by one.
 *   A multi-register type with [step] off is rejected because neighbours would share registers.
 * - [unitId] selects the slave behind a shared address; null uses the device's unit id.
 * - [modbusType] is a designator of this driver's address syntax (see [ModbusAddress]), for example HR,
 *   HRUS, HRI, HRF, HRD, HRBCD, IR, IRF, C, DI. Strings (HRS) cannot be mapped, as on the public page.
 * - [modbusAddress] is the first register or coil number, in the device's own one- or zero-based
 *   numbering, without the 4xxxx table digit.
 *
 * Word order and byte order are device settings in this driver, so a mapped 32-bit item uses the
 * device's word order like any other address.
 */
data class ModbusAddressRange(
    val prefix: String,
    val start: String,
    val end: String,
    val modbusType: String,
    val modbusAddress: Int,
    val radix: Int = 10,
    val step: Boolean = false,
    val unitId: Int? = null,
)

/** One generated item: [name] is prefix + number in the range's radix, [address] is a driver address. */
data class MappedAddress(
    val name: String,
    val address: String,
    val type: ModbusType,
    val area: ModbusArea,
)

object ModbusAddressMapping {
    /** Upper bound on items one request may generate. */
    const val MAX_ITEMS = 65_536

    private val PREFIX = Regex("^[A-Za-z0-9_]{1,64}$")
    private val RESERVED = setOf("HR", "IR", "C", "DI")

    /** Designators a range may use: every numeric and boolean designator of [ModbusAddress]. */
    val DESIGNATORS: Set<String> = linkedSetOf(
        "HR", "HRUS", "HRI", "HRUI", "HRI_64", "HRUI_64", "HRF", "HRD", "HRBCD", "HRBCD_32",
        "IR", "IRUS", "IRI", "IRUI", "IRI_64", "IRUI_64", "IRF", "IRD", "IRBCD", "IRBCD_32",
        "C", "DI",
    )

    /**
     * Expand [range] for [device]. Each address is validated with the driver's own parser, so an address
     * that runs past register 65535 is an [AddressException], not a tag with a bad address.
     */
    fun expand(range: ModbusAddressRange, device: ModbusDeviceConfig): List<MappedAddress> {
        if (!PREFIX.matches(range.prefix)) throw AddressException("prefix may contain only letters, digits, and underscores")
        if (range.prefix.uppercase() in RESERVED) throw AddressException("prefix '${range.prefix}' is reserved")
        if (range.radix !in 2..36) throw AddressException("radix ${range.radix} is outside 2..36")
        val first = number(range.start, range.radix, "start")
        val last = number(range.end, range.radix, "end")
        if (last < first) throw AddressException("end is before start")
        val count = last - first + 1
        if (count > MAX_ITEMS) throw AddressException("range has $count items; the limit is $MAX_ITEMS")
        val unit = range.unitId ?: device.unitId
        if (unit !in 0..255) throw AddressException("unit id $unit is outside 0..255")
        val designator = range.modbusType.trim().uppercase()
        if (designator == "HRS") throw AddressException("string types cannot be address-mapped")
        if (designator !in DESIGNATORS) throw AddressException("unknown Modbus type '${range.modbusType}'")
        val probe = ModbusAddress.parse("$unit.$designator${range.modbusAddress}", device)
        if (probe.type == ModbusType.STRING) throw AddressException("string types cannot be address-mapped")
        if (probe.span > 1 && !range.step) {
            throw AddressException("${range.modbusType} spans ${probe.span} registers; turn on step so items do not overlap")
        }
        val stride = if (range.step) probe.span else 1
        val out = ArrayList<MappedAddress>(count)
        for (i in 0 until count) {
            val register = range.modbusAddress.toLong() + i.toLong() * stride
            if (register > 65_536) throw AddressException("range runs past register 65535")
            val address = "$unit.$designator$register"
            val parsed = ModbusAddress.parse(address, device)
            out += MappedAddress(range.prefix + (first + i).toString(range.radix).uppercase(), address, parsed.type, parsed.area)
        }
        return out
    }

    private fun number(text: String, radix: Int, what: String): Int {
        val value = text.trim().toIntOrNull(radix) ?: throw AddressException("$what '$text' is not a base-$radix number")
        if (value !in 0..65_535) throw AddressException("$what $value is outside 0..65535")
        return value
    }
}
