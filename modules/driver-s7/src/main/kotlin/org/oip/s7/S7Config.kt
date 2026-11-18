package org.oip.s7

/**
 * One S7-300, S7-400, S7-1200, or S7-1500 device over ISO-on-TCP.
 *
 * Defaults where the public device page states one (port 102, timeout 2000 ms, PDU size 240,
 * rack 0, CPU slot 2). Doc source: FEATURES DEV-025. Rack and slot limits: DIFFERENCES 322.
 *
 * Rack and slot are encoded into the COTP remote TSAP as
 * `(connectionType << 8) | (rack << 5) | slot`. [localTsap] and [remoteTsap], when set, replace
 * the computed TSAPs, which is how a connection that is not rack/slot addressed is configured.
 *
 * [commsLossQuality] is applied to subscribed values when the socket drops or the request times
 * out. The last value is kept. SPEC §5 also allows Uncertain_LastKnownValue per tag.
 *
 * A response timeout always resets the ISO session. S7 jobs are paired by PDU reference, so a
 * late reply must not be applied to the next read. [reconnectAfterConsecutiveTimeouts] is accepted
 * (default 3) and does not add a second wait; the session still resets on the first timeout
 * (DIFFERENCES 323).
 *
 * [symbols] is the device's imported symbol table (config field `symbolTable`, CSV text). It is
 * parsed when the config loads. Doc source: FEATURES DEV-026.
 */
data class S7DeviceConfig(
    val name: String,
    val host: String,
    val port: Int = 102,
    val model: S7Model = S7Model.S7_300,
    val timeoutMs: Long = 2_000,
    val localAddress: String? = null,
    val pduSize: Int = 240,
    val rack: Int = 0,
    val slot: Int = 2,
    val connectionType: S7ConnectionType = S7ConnectionType.PG,
    val localTsap: Int? = null,
    val remoteTsap: Int? = null,
    val spanGaps: Boolean = true,
    val maxGap: Int = 0,
    val reconnectAfterConsecutiveTimeouts: Int = 3,
    val backoffInitialMs: Long = 500,
    val backoffMaxMs: Long = 30_000,
    val backoffMultiplier: Double = 2.0,
    val defaultScanPeriodMs: Long = 1_000,
    val commsLossQuality: QualityCode = QualityCode.Bad_NotConnected,
    val description: String = "",
    /** Symbols for `"Block".member` and `DB10.member` tag addresses; see [S7SymbolTable]. */
    val symbols: S7SymbolTable = S7SymbolTable.EMPTY,
) {
    init {
        require(name.isNotBlank()) { "device name is required" }
        require(host.isNotBlank()) { "host is required" }
        require(port in 1..65535) { "port $port" }
        require(timeoutMs > 0)
        require(pduSize in 240..960) { "PDU size $pduSize is outside 240..960" }
        require(rack in 0..7) { "rack $rack is outside 0..7" }
        require(slot in 0..31) { "slot $slot is outside 0..31" }
        require(localTsap == null || localTsap in 0..65535)
        require(remoteTsap == null || remoteTsap in 0..65535)
        require(maxGap >= 0)
        require(reconnectAfterConsecutiveTimeouts >= 1)
        require(backoffInitialMs > 0 && backoffMaxMs >= backoffInitialMs)
        require(backoffMultiplier >= 1.0)
        require(defaultScanPeriodMs >= 10)
        require(
            commsLossQuality == QualityCode.Bad_NotConnected ||
                commsLossQuality == QualityCode.Uncertain_LastKnownValue ||
                commsLossQuality == QualityCode.Bad_NoCommunication,
        ) { "comms-loss quality must be a connection-loss code" }
    }

    /** Calling TSAP. The default is the connection type in the high byte (PG is 0x0100). */
    fun resolvedLocalTsap(): Int = localTsap ?: (connectionType.code shl 8)

    /**
     * Called TSAP. Rack and slot use the public PG/OP encoding:
     * high byte is the connection type, low byte is `(rack << 5) | slot`.
     */
    fun resolvedRemoteTsap(): Int =
        remoteTsap ?: ((connectionType.code shl 8) or ((rack and 0x7) shl 5) or (slot and 0x1F))
}

/**
 * Figures for `Gateway/Device/<name>/Diagnostics`, the same set the Modbus and Logix drivers
 * publish. Requests count every acknowledged S7 job (setup, read, write). Sampling and publish
 * are the fastest active scan, or the default scan period when nothing is subscribed.
 * doc source: FEATURES DEV-004
 */
data class S7DeviceDiagnostics(
    val connected: Boolean,
    val state: String,
    val requestCount: Long,
    val errorCount: Long,
    val timeoutCount: Long,
    val averageResponseTimeMs: Double?,
    val samplingIntervalMs: Double,
    val publishIntervalMs: Double,
)

/** COTP connection class used to build the default TSAPs. PG is the usual programming connection. */
enum class S7ConnectionType(val code: Int) { PG(1), OP(2), BASIC(3) }
