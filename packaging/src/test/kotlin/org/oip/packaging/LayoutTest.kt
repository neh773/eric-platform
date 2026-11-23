package org.oip.packaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Properties

class LayoutTest {
    private val layout = Layout(Layout.DEFAULT_PRODUCT_NAME)

    @Test fun onePropertyFeedsDisplayVendorAndExecutableNames() {
        assertEquals("ERIC", layout.vendor)
        assertEquals("ERIC Gateway", layout.gatewayName)
        assertEquals("Control Canvas", layout.designerName)
        assertEquals("ERIC Gateway", layout.serviceName)
        assertEquals("C:\\ProgramData\\ERIC\\gateway", layout.dataDir)
        val image = layout.gatewayImageArgs("0.1.0", "/in", "server.jar", "/out")
        assertEquals(layout.vendor, image[image.indexOf("--vendor") + 1])
        assertEquals(layout.gatewayName, image[image.indexOf("--name") + 1])
        val designer = layout.designerImageArgs("0.1.0", "/in", "designer.jar", "/out")
        assertEquals(layout.designerName, designer[designer.indexOf("--name") + 1])
        assertEquals(layout.vendor, designer[designer.indexOf("--vendor") + 1])
    }

    @Test fun gradlePropertyIsTheDefaultNameAndUuidsStayStable() {
        val file = System.getProperty("oip.gradleProperties")
        val props = Properties().apply { java.io.File(file).reader().use { load(it) } }
        assertEquals(Layout.DEFAULT_PRODUCT_NAME, props.getProperty("oip.productName"))
        Layout.requireUuid(props.getProperty("oip.gatewayUpgradeUuid"))
        Layout.requireUuid(props.getProperty("oip.designerUpgradeUuid"))
        assertEquals("6f0e2a41-9c5b-4d7e-8a13-2b6d4f8e0c71", props.getProperty("oip.gatewayUpgradeUuid"))
        assertEquals("b7d31c90-4e2a-4f58-9c6b-1a0e7d5c3f28", props.getProperty("oip.designerUpgradeUuid"))
    }

    @Test fun snapshotVersionBecomesAWindowsVersion() {
        assertEquals("0.1.0", layout.appVersion("0.1.0-SNAPSHOT"))
        assertThrows<IllegalArgumentException> { layout.appVersion("snapshot") }
    }

    @Test fun imageArgsBundleARuntimeAndDoNotAskForAnInstaller() {
        val args = layout.gatewayImageArgs("0.1.0", "/in", "server.jar", "/out")
        assertEquals("app-image", args[args.indexOf("--type") + 1])
        assertTrue(args.contains("--jlink-options"))
        assertTrue(args[args.indexOf("--jlink-options") + 1].contains("--compress"))
        assertEquals("open-admin", args[args.indexOf("--arguments") + 1])
        assertEquals(Layout.GATEWAY_MAIN, args[args.indexOf("--main-class") + 1])
        assertFalse(args.contains("--launcher-as-service"))
        assertFalse(args.contains("--file-associations"))
        assertFalse(args.any { it.contains("netsh") || it.contains("firewall") })
    }

    @Test fun exeArgsRegisterAServiceAndKeepTheUpgradeUuid() {
        val args = layout.gatewayExeArgs("0.1.0", "/in", "server.jar", "/out", "/res", "6f0e2a41-9c5b-4d7e-8a13-2b6d4f8e0c71")
        assertEquals("exe", args[args.indexOf("--type") + 1])
        assertTrue(args.contains("--launcher-as-service"))
        assertTrue(args.contains("--win-menu"))
        assertTrue(args.contains("--win-shortcut"))
        assertEquals("6f0e2a41-9c5b-4d7e-8a13-2b6d4f8e0c71", args[args.indexOf("--win-upgrade-uuid") + 1])
        assertEquals("/res", args[args.indexOf("--resource-dir") + 1])
        assertTrue(args.contains("--icon"))
        assertTrue(args.contains("--license-file"))
        val designer = layout.designerExeArgs("0.1.0", "/in", "d.jar", "/out", "b7d31c90-4e2a-4f58-9c6b-1a0e7d5c3f28", "/res")
        assertFalse(designer.contains("--launcher-as-service"))
        assertTrue(designer.contains("--win-menu"))
        assertTrue(designer.contains("--win-shortcut"))
        assertFalse(args.contains("--file-associations"))
    }

    @Test fun serviceConfigPointsAtProgramDataAndDoesNotRemoveIt() {
        val wxi = layout.serviceConfigWxi()
        assertTrue(wxi.contains("Name=\"AppParameters\""))
        assertTrue(wxi.contains("AppEnvironmentExtra"))
        assertTrue(wxi.contains("ERIC_ADMIN_DIST=[INSTALLDIR]app\\admin"))
        assertTrue(wxi.contains("ERIC_WEB_DIST=[INSTALLDIR]app\\web"))
        assertTrue(wxi.contains("ERIC_DOCS_DIR=[INSTALLDIR]app\\docs"))
        assertTrue(wxi.contains("ERIC_PLC_COMPILER=[INSTALLDIR]app\\plc\\rusty\\plc.exe"))
        assertFalse(wxi.contains("OIP_ADMIN_DIST"))
        assertFalse(wxi.contains("OIP_WEB_DIST"))
        assertFalse(wxi.contains("OIP_DOCS_DIR"))
        assertTrue(wxi.contains("Name=\"AppStdout\""))
        assertTrue(wxi.contains("[INSTALLDIR]service-stdout.log"))
        assertTrue(wxi.contains("Name=\"AppStopMethodConsole\""))
        assertTrue(wxi.contains("Value=\"15000\""))
        assertFalse(wxi.contains("FirewallRules"))
        assertFalse(wxi.contains("<util:"))
        assertFalse(wxi.contains("<fire:"))
        assertFalse(wxi.contains("InstallExecuteSequence"))
        assertTrue(wxi.contains("--data-dir &quot;C:\\ProgramData\\ERIC\\gateway&quot;"))
        assertTrue(wxi.contains("APPLICATION_LAUNCHER"))
        assertFalse(wxi.contains("RemoveFolder"))
        assertFalse(wxi.contains("ProgramData") && wxi.contains("RemoveFile"))
        val escaped = Layout("A & B").serviceConfigWxi()
        assertTrue(escaped.contains("A &amp; B"))
        assertFalse(escaped.contains("A & B"))
    }
}
