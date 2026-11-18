package org.oip.gateway

/**
 * The host operating system, as the gateway process sees it.
 * The PLC screens use this to decide the Windows banner. The browser's OS is not consulted.
 */
object HostPlatform {
    const val BANNER =
        "This PLC is running on a Windows PC. Windows can't give the PLC truly dedicated CPU cores, memory, or interrupt-free timing, so scan timing isn't guaranteed and it does not behave like a real PLC. Use it for development, testing, training, and non-critical slow process control only — never for safety or fast motion. For real-time control, run the ERIC PLC on a Linux computer with a real-time kernel."

    fun osName(property: String = System.getProperty("os.name") ?: ""): String = when {
        property.contains("win", ignoreCase = true) -> "windows"
        property.contains("linux", ignoreCase = true) -> "linux"
        property.contains("mac", ignoreCase = true) -> "mac"
        else -> "other"
    }

    fun body(property: String = System.getProperty("os.name") ?: ""): Map<String, Any?> {
        val os = osName(property)
        return mapOf(
            "os" to os,
            "windowsPlc" to (os == "windows"),
            "banner" to BANNER,
        )
    }
}
