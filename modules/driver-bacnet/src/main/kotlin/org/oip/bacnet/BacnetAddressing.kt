package org.oip.bacnet

/**
 * Item addresses of a remote BACnet device (DEV-038).
 *
 * An object is `<object-type>:<instance>`, with the standard type name in lower case (`analog-input:3`,
 * `binary-output:1`) or `object-type-<n>` for a type without a name here. Its items are
 * `<object>/<Item>`: `PresentValue`, `ObjectName`, `Description`, `Units`, `OutOfService`, `Reliability`,
 * `RelinquishDefault`, `StatusFlags/InAlarm`, `StatusFlags/Fault`, `StatusFlags/Overridden`,
 * `StatusFlags/OutOfService`, or a property by its number (`analog-input:3/85`). A present-value write
 * can name its priority with `;priority=<1..16>` (`analog-output:1/PresentValue;priority=8`).
 * doc source: FEATURES DEV-038
 */
data class BacnetItemAddress(val obj: ObjectId, val item: BacnetItem?, val priority: Int? = null) {
    /** True for the object itself (a folder); false for a property item. */
    val isObject: Boolean get() = item == null

    val property: Int get() = item?.property ?: -1

    override fun toString(): String = buildString {
        append(obj)
        if (item != null) { append('/'); append(item.path) }
        if (priority != null) append(";priority=").append(priority)
    }

    companion object {
        fun parse(text: String): BacnetItemAddress {
            var body = text.trim()
            var priority: Int? = null
            val semi = body.indexOf(';')
            if (semi >= 0) {
                val option = body.substring(semi + 1).trim()
                body = body.substring(0, semi)
                val p = option.removePrefix("priority=")
                if (p == option) throw AddressException("unknown option '$option' in '$text'")
                priority = p.toIntOrNull()?.takeIf { it in 1..16 } ?: throw AddressException("priority must be 1..16 in '$text'")
            }
            val slash = body.indexOf('/')
            val objText = if (slash >= 0) body.substring(0, slash) else body
            val colon = objText.lastIndexOf(':')
            if (colon <= 0) throw AddressException("'$text' is not <object-type>:<instance>")
            val type = BacnetObjectType.parse(objText.substring(0, colon)) ?: throw AddressException("unknown object type in '$text'")
            val instance = objText.substring(colon + 1).toIntOrNull()?.takeIf { it in 0..ObjectId.MAX_INSTANCE }
                ?: throw AddressException("bad object instance in '$text'")
            val obj = ObjectId(type, instance)
            if (slash < 0) {
                if (priority != null) throw AddressException("a priority needs a property in '$text'")
                return BacnetItemAddress(obj, null)
            }
            val item = BacnetItem.parse(body.substring(slash + 1)) ?: throw AddressException("unknown item in '$text'")
            if (priority != null && item.property != BacnetProperty.PRESENT_VALUE) throw AddressException("only PresentValue takes a priority in '$text'")
            return BacnetItemAddress(obj, item, priority)
        }
    }
}

/** A readable item of an object: one property, or one bit of the status flags. */
data class BacnetItem(val path: String, val property: Int, val bit: Int? = null) {
    companion object {
        val PRESENT_VALUE = BacnetItem("PresentValue", BacnetProperty.PRESENT_VALUE)
        val OBJECT_NAME = BacnetItem("ObjectName", BacnetProperty.OBJECT_NAME)
        val DESCRIPTION = BacnetItem("Description", BacnetProperty.DESCRIPTION)
        val UNITS = BacnetItem("Units", BacnetProperty.UNITS)
        val OUT_OF_SERVICE = BacnetItem("OutOfService", BacnetProperty.OUT_OF_SERVICE)
        val RELIABILITY = BacnetItem("Reliability", BacnetProperty.RELIABILITY)
        val RELINQUISH_DEFAULT = BacnetItem("RelinquishDefault", BacnetProperty.RELINQUISH_DEFAULT)
        val VENDOR_NAME = BacnetItem("VendorName", BacnetProperty.VENDOR_NAME)
        val VENDOR_IDENTIFIER = BacnetItem("VendorIdentifier", BacnetProperty.VENDOR_IDENTIFIER)
        val MODEL_NAME = BacnetItem("ModelName", BacnetProperty.MODEL_NAME)
        val FIRMWARE_REVISION = BacnetItem("FirmwareRevision", BacnetProperty.FIRMWARE_REVISION)
        val SYSTEM_STATUS = BacnetItem("SystemStatus", BacnetProperty.SYSTEM_STATUS)
        /** BACnetStatusFlags bit order: in-alarm, fault, overridden, out-of-service. */
        val IN_ALARM = BacnetItem("StatusFlags/InAlarm", BacnetProperty.STATUS_FLAGS, 0)
        val FAULT = BacnetItem("StatusFlags/Fault", BacnetProperty.STATUS_FLAGS, 1)
        val OVERRIDDEN = BacnetItem("StatusFlags/Overridden", BacnetProperty.STATUS_FLAGS, 2)
        val OUT_OF_SERVICE_FLAG = BacnetItem("StatusFlags/OutOfService", BacnetProperty.STATUS_FLAGS, 3)

        const val STATUS_FLAGS_FOLDER = "StatusFlags"

        private val named = listOf(
            PRESENT_VALUE, OBJECT_NAME, DESCRIPTION, UNITS, OUT_OF_SERVICE, RELIABILITY, RELINQUISH_DEFAULT,
            VENDOR_NAME, VENDOR_IDENTIFIER, MODEL_NAME, FIRMWARE_REVISION, SYSTEM_STATUS,
            IN_ALARM, FAULT, OVERRIDDEN, OUT_OF_SERVICE_FLAG,
        ).associateBy { it.path.lowercase() }

        fun parse(text: String): BacnetItem? {
            named[text.lowercase()]?.let { return it }
            val number = text.toIntOrNull() ?: return null
            if (number !in 0..4_194_303) return null
            return BacnetItem(number.toString(), number)
        }

        val STATUS_BITS = listOf(IN_ALARM, FAULT, OVERRIDDEN, OUT_OF_SERVICE_FLAG)

        /** The items browse lists under an object of [type]. */
        fun itemsFor(type: Int): List<BacnetItem> = when {
            type == BacnetObjectType.DEVICE -> listOf(OBJECT_NAME, DESCRIPTION, VENDOR_NAME, VENDOR_IDENTIFIER, MODEL_NAME, FIRMWARE_REVISION, SYSTEM_STATUS)
            BacnetObjectType.isAnalog(type) -> listOfNotNull(
                OBJECT_NAME, PRESENT_VALUE, DESCRIPTION, UNITS, OUT_OF_SERVICE, RELIABILITY,
                RELINQUISH_DEFAULT.takeIf { BacnetObjectType.isCommandable(type) },
            )
            BacnetObjectType.isBinary(type) || BacnetObjectType.isMultiState(type) -> listOfNotNull(
                OBJECT_NAME, PRESENT_VALUE, DESCRIPTION, OUT_OF_SERVICE, RELIABILITY,
                RELINQUISH_DEFAULT.takeIf { BacnetObjectType.isCommandable(type) },
            )
            else -> listOf(OBJECT_NAME, PRESENT_VALUE, DESCRIPTION)
        }

        fun hasStatusFlags(type: Int): Boolean = type != BacnetObjectType.DEVICE

        /** Whether a client may write [item] of an object of [type]. An input's present value is writable only out of service. */
        fun writable(type: Int, item: BacnetItem): Boolean = when {
            item.bit != null -> false
            item.property == BacnetProperty.PRESENT_VALUE -> type != BacnetObjectType.DEVICE
            item.property == BacnetProperty.OUT_OF_SERVICE -> true
            item.property == BacnetProperty.RELINQUISH_DEFAULT -> BacnetObjectType.isCommandable(type)
            item.property == BacnetProperty.DESCRIPTION -> true
            item.path.toIntOrNull() != null -> true
            else -> false
        }
    }
}

/** Convert decoded BACnet values to plain tag values. */
object BacnetValues {
    fun forItem(type: Int, item: BacnetItem, raw: Any?): Any? {
        if (item.bit != null) return (raw as? BacnetBitString)?.get(item.bit) ?: throw TypeMismatchException("status flags were not a bit string")
        if (item.property == BacnetProperty.PRESENT_VALUE || item.property == BacnetProperty.RELINQUISH_DEFAULT) {
            if (BacnetObjectType.isBinary(type) && raw is BacnetEnumerated) return raw.value == 1L
        }
        return plain(raw)
    }

    fun plain(raw: Any?): Any? = when (raw) {
        null, BacnetNull -> null
        is BacnetUnsigned -> raw.value
        is BacnetEnumerated -> raw.value
        is Float -> raw.toDouble()
        is BacnetBitString -> raw.bits
        is ObjectId -> raw.toString()
        is ContextRaw -> raw.bytes.joinToString("") { "%02x".format(it) }
        is List<*> -> raw.map { plain(it) }
        else -> raw
    }

    /** The BACnet value to write to [item] of an object of [type]. Null relinquishes a commanded value. */
    fun forWrite(type: Int, item: BacnetItem, value: Any?): Any? {
        if (value == null) {
            if (item.property == BacnetProperty.PRESENT_VALUE && BacnetObjectType.isCommandable(type)) return BacnetNull
            throw TypeMismatchException("only a commandable present value can be relinquished with null")
        }
        val p = item.property
        if (p == BacnetProperty.OUT_OF_SERVICE) return bool(value)
        if (p == BacnetProperty.DESCRIPTION || p == BacnetProperty.OBJECT_NAME) return value.toString()
        if (p == BacnetProperty.PRESENT_VALUE || p == BacnetProperty.RELINQUISH_DEFAULT) {
            return when {
                BacnetObjectType.isAnalog(type) -> number(value).toFloat()
                BacnetObjectType.isBinary(type) -> BacnetEnumerated(if (bool(value)) 1 else 0)
                BacnetObjectType.isMultiState(type) -> {
                    val n = number(value)
                    if (n != Math.floor(n) || n < 1 || n > 0xFFFFFFFFL) throw OutOfRangeException("a multi-state value is a whole number from 1")
                    BacnetUnsigned(n.toLong())
                }
                else -> value
            }
        }
        return value
    }

    private fun number(v: Any): Double = when (v) {
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull() ?: throw TypeMismatchException("'$v' is not a number")
        else -> throw TypeMismatchException("${v::class.simpleName} is not a number")
    }

    private fun bool(v: Any): Boolean = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> when (v.trim().lowercase()) {
            "true", "1", "active", "on" -> true
            "false", "0", "inactive", "off" -> false
            else -> throw TypeMismatchException("'$v' is not a boolean")
        }
        else -> throw TypeMismatchException("${v::class.simpleName} is not a boolean")
    }
}
