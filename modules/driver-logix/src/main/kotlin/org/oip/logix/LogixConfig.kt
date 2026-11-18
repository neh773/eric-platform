package org.oip.logix

/**
 * One ControlLogix or CompactLogix device over EtherNet/IP.
 *
 * Field names follow the public Logix driver page. Port 44818 is the EtherNet/IP port that page
 * names. Slot 0 is the "not in a rack" value. A blank [connectionPath] is sent as `1,<slot>`
 * (backplane, then the processor slot).
 *
 *
 * The public page does not publish a numeric default for CIP connection size or identity period.
 * This driver uses 500 bytes (a standard Forward Open) and an identity read every 5 s.
 * [maxConcurrentRequests] is how many Class 3 connections are opened; each one carries its own
 * Multiple Service Packet stream.
 *
 * [symbolInstanceAddressing] names a browsed tag's root by its Symbol object instance id in
 * Read/Write Tag requests (1756-PM020 symbol instance addressing) instead of its name. Tags the
 * catalog does not know yet stay symbolic. Off by default, so a controller download that
 * renumbers symbols cannot point a request at another tag before the catalog is reloaded.
 * doc source: FEATURES DEV-010
 */
data class LogixDeviceConfig(
    val name: String,
    val host: String,
    val port: Int = 44818,
    val description: String = "",
    val localAddress: String? = null,
    val timeoutMs: Long = 2_000,
    val connectionPath: String = "",
    val slot: Int = 0,
    val cipConnectionSize: Int = 500,
    val cipConnectionTimeoutMs: Long = 8_000,
    val identityRequestFrequencyMs: Long = 5_000,
    val maxConcurrentRequests: Int = 1,
    val automaticRebrowse: Boolean = true,
    val symbolInstanceAddressing: Boolean = false,
    val backoffInitialMs: Long = 500,
    val backoffMaxMs: Long = 30_000,
    val backoffMultiplier: Double = 2.0,
    val defaultScanPeriodMs: Long = 1_000,
    val commsLossQuality: QualityCode = QualityCode.Bad_NotConnected,
    val profile: LogixProfile = LogixProfile.LOGIX,
) {
    init {
        require(name.isNotBlank()) { "device name is required" }
        require(host.isNotBlank()) { "host is required" }
        require(port in 1..65535) { "port $port" }
        require(timeoutMs > 0)
        require(slot in 0..31) { "slot $slot" }
        require(cipConnectionSize in 64..4000) { "CIP connection size $cipConnectionSize" }
        require(cipConnectionTimeoutMs > 0)
        require(identityRequestFrequencyMs >= 0)
        require(maxConcurrentRequests in 1..8)
        require(backoffInitialMs > 0 && backoffMaxMs >= backoffInitialMs)
        require(backoffMultiplier >= 1.0)
        require(defaultScanPeriodMs >= 10)
        require(
            commsLossQuality == QualityCode.Bad_NotConnected ||
                commsLossQuality == QualityCode.Uncertain_LastKnownValue ||
                commsLossQuality == QualityCode.Bad_NoCommunication,
        ) { "comms-loss quality must be a connection-loss code" }
        require(cipConnectionSize <= profile.maxConnectionSize) {
            "CIP connection size $cipConnectionSize is above ${profile.maxConnectionSize}, the largest the ${profile.id} profile opens"
        }
        require(!symbolInstanceAddressing || profile.instanceAddressing) {
            "symbol instance addressing is not available on the ${profile.id} profile"
        }
        if (profile.slotless) require(slot == 0) { "the ${profile.id} profile has no slot setting; use connectionPath" }
        if (connectionPath.isNotBlank()) Cip.routeOf(parseConnectionPath(connectionPath))
    }

    /**
     * Route bytes for Forward Open and Unconnected Send. A blank [connectionPath] is `1,<slot>` on
     * a chassis controller, `1,0` on a CompactLogix (the processor sits in the first slot), and no
     * route on a Micro800, which is addressed directly. doc source: FEATURES DEV-009, DEV-017, DEV-023
     */
    internal fun route(): ByteArray = when {
        connectionPath.isNotBlank() -> Cip.route(connectionPath, slot)
        profile == LogixProfile.MICRO800 -> ByteArray(0)
        else -> Cip.route("", slot)
    }
}

/**
 * Which controller family a Logix device talks to. One driver serves all four; the profile picks
 * the services the controller has.
 *
 * - [LOGIX]: firmware 21 and later. Large Forward Open, symbol instance addressing, program scope,
 *   UDT templates, automatic rebrowse on a catalog change.
 * - [LEGACY_CONTROLLOGIX], [LEGACY_COMPACTLOGIX]: firmware 20 and earlier (the public legacy
 *   pages cover firmware up to 20.18). Standard Forward Open only, so the connection is at most
 *   511 bytes; tags are always addressed by name; the tag tree is browsed when the connection
 *   comes up and is not watched for changes. A legacy CompactLogix has a connection path and no
 *   slot setting. doc source: FEATURES DEV-021, DEV-023
 * - [MICRO800]: global variables only, no program scope, no UDT browse (a member is read by its
 *   path), CIP SHORT_STRING strings of at most 80 characters, the unsigned and bit-string types
 *   the Micro800 page lists, standard Forward Open only. doc source: FEATURES DEV-017
 */
enum class LogixProfile(
    val id: String,
    val maxConnectionSize: Int,
    val largeForwardOpen: Boolean,
    val instanceAddressing: Boolean,
    val programScope: Boolean,
    val templates: Boolean,
    val watchesCatalog: Boolean,
    val slotless: Boolean,
) {
    LOGIX("logix", 4000, true, true, true, true, true, false),
    LEGACY_CONTROLLOGIX("legacy-controllogix", 511, false, false, true, true, false, false),
    LEGACY_COMPACTLOGIX("legacy-compactlogix", 511, false, false, true, true, false, true),
    MICRO800("micro800", 511, false, false, false, false, false, true),
    ;

    companion object {
        fun of(id: String): LogixProfile =
            entries.firstOrNull { it.id == id } ?: throw IllegalArgumentException("unknown profile '$id'")
    }
}

/** Figures for `Gateway/Device/<name>/Diagnostics`. Sampling and publish match the fastest scan. */
data class DeviceDiagnostics(
    val connected: Boolean,
    val state: String,
    val requestCount: Long,
    val errorCount: Long,
    val timeoutCount: Long,
    val averageResponseTimeMs: Double?,
    val samplingIntervalMs: Double,
    val publishIntervalMs: Double,
)
