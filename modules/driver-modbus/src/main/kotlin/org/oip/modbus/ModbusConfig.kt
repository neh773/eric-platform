package org.oip.modbus

/**
 * One Modbus TCP or RTU-over-TCP device.
 *
 * Defaults follow the public the reference platform device page where that page states a default
 * (port 502, 125 registers, 2000 coils, span gaps, reverse word order off, one-based addresses):
 *
 * [maxGap] is the largest number of unused coils or registers the optimizer may include between
 * two items when [spanGaps] is true. the reference platform's Span Gaps checkbox is [spanGaps]; with it on and
 * [maxGap] left at [Int.MAX_VALUE], any gap that still fits in one request is spanned.
 *
 * [commsLossQuality] is applied to subscribed values when the socket drops. The last value is kept.
 * SPEC §5 also allows Uncertain_LastKnownValue per tag; set this to [QualityCode.Uncertain_LastKnownValue]
 * for that behavior. The default is Bad_NotConnected.
 */
data class ModbusDeviceConfig(
    val name: String,
    val host: String,
    val port: Int = 502,
    val mode: ModbusMode = ModbusMode.TCP,
    val unitId: Int = 1,
    val timeoutMs: Long = 1_000,
    val localAddress: String? = null,
    val zeroBasedAddressing: Boolean = false,
    val wordOrder: WordOrder = WordOrder.HIGH_WORD_FIRST,
    val byteOrder: ByteOrder = ByteOrder.BIG_ENDIAN,
    val reverseStringByteOrder: Boolean = false,
    val rightJustifyStrings: Boolean = false,
    val readRawStrings: Boolean = false,
    val spanGaps: Boolean = true,
    val maxGap: Int = Int.MAX_VALUE,
    val maxHoldingRegistersPerRead: Int = 125,
    val maxHoldingRegistersPerWrite: Int = 123,
    val maxInputRegistersPerRequest: Int = 125,
    val maxCoilsPerRequest: Int = 2000,
    val maxDiscreteInputsPerRequest: Int = 2000,
    /** In-flight requests on Modbus TCP. RTU over TCP is always one at a time (no transaction id). */
    val concurrentRequests: Int = 1,
    val allowWriteMultipleRegisters: Boolean = true,
    val forceMultipleRegisterWrites: Boolean = false,
    val allowWriteMultipleCoils: Boolean = true,
    val allowReadMultipleRegisters: Boolean = true,
    val allowReadMultipleCoils: Boolean = true,
    val allowReadMultipleDiscreteInputs: Boolean = true,
    /** Drop the socket after this many consecutive response timeouts. the reference platform's default behavior is 3. */
    val reconnectAfterConsecutiveTimeouts: Int = 3,
    /** Extra attempts after a Modbus exception response. the reference platform's "Max Retry Count" default is 1. */
    val maxRetryCount: Int = 1,
    val backoffInitialMs: Long = 500,
    val backoffMaxMs: Long = 30_000,
    val backoffMultiplier: Double = 2.0,
    val defaultScanPeriodMs: Long = 1_000,
    val commsLossQuality: QualityCode = QualityCode.Bad_NotConnected,
) {
    init {
        require(name.isNotBlank()) { "device name is required" }
        require(port in 1..65535) { "port $port" }
        require(unitId in 0..255) { "unit id $unitId" }
        require(timeoutMs > 0)
        require(backoffInitialMs > 0 && backoffMaxMs >= backoffInitialMs)
        require(backoffMultiplier >= 1.0)
        require(defaultScanPeriodMs >= 10)
        require(concurrentRequests >= 1)
        require(reconnectAfterConsecutiveTimeouts >= 1)
        require(maxRetryCount >= 0)
        require(
            commsLossQuality == QualityCode.Bad_NotConnected ||
                commsLossQuality == QualityCode.Uncertain_LastKnownValue ||
                commsLossQuality == QualityCode.Bad_NoCommunication,
        ) { "comms-loss quality must be a connection-loss code" }
    }
}

enum class ModbusMode { TCP, RTU_OVER_TCP }
