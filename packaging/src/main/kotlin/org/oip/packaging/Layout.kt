package org.oip.packaging

import java.io.File
import java.util.UUID

/**
 * Names that follow [productName]. Display name, vendor, and executable names
 * all come from that one string. Gateway and designer installers add a fixed role suffix.
 */
data class Layout(val productName: String) {
    val vendor: String = productName
    val gatewayName: String = "$productName Gateway"
    val designerName: String = "Control Canvas"
    /** Second launcher with its own Start-menu shortcut: waits for the service, then opens the admin page. */
    val adminLauncherName: String = "$productName Admin"
    val adminUrl: String = "http://localhost:$DEFAULT_HTTP_PORT/admin"
    /** Where the installer leaves a copy of its log, even after a successful install. */
    val installLog: String = "C:\\ProgramData\\$productName\\logs\\install.log"
    /** Service key and the Start-menu shortcut that opens the admin UI. */
    val serviceName: String = gatewayName
    val dataDir: String = "C:\\ProgramData\\$productName\\gateway"
    val menuGroup: String = productName

    init {
        require(productName.isNotBlank()) { "product name is empty" }
        require(productName.none { it in INVALID_NAME }) {
            "product name cannot contain $INVALID_NAME"
        }
    }

    fun appVersion(gradleVersion: String): String {
        val numeric = gradleVersion.substringBefore('-')
        val parts = numeric.split('.')
        require(parts.size in 2..4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            "jpackage needs a numeric version with 2 to 4 components, got '$gradleVersion'"
        }
        return numeric
    }

    fun serviceConfigWxi(): String {
        val quoted = "&quot;${xml(dataDir)}&quot;"
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <Include xmlns="http://schemas.microsoft.com/wix/2006/wi">
              <DirectoryRef Id="INSTALLDIR">
                <Component Id="nssm_Application_APPLICATION_LAUNCHER_ID" Guid="*">
                  <RegistryValue Root="HKLM"
                                 Key="System\CurrentControlSet\Services\SERVICE_NAME\Parameters"
                                 Type="string"
                                 KeyPath="yes"
                                 Name="Application"
                                 Value="APPLICATION_LAUNCHER"/>
                </Component>
                <Component Id="nssm_AppDirectory_APPLICATION_LAUNCHER_ID" Guid="*">
                  <RegistryValue Root="HKLM"
                                 Key="System\CurrentControlSet\Services\SERVICE_NAME\Parameters"
                                 Type="string"
                                 KeyPath="yes"
                                 Name="AppDirectory"
                                 Value="[INSTALLDIR]"/>
                </Component>
                <Component Id="nssm_AppParameters_APPLICATION_LAUNCHER_ID" Guid="*">
                  <RegistryValue Root="HKLM"
                                 Key="System\CurrentControlSet\Services\SERVICE_NAME\Parameters"
                                 Type="string"
                                 KeyPath="yes"
                                 Name="AppParameters"
                                 Value="--data-dir $quoted"/>
                  <RegistryValue Root="HKLM"
                                 Key="System\CurrentControlSet\Services\SERVICE_NAME\Parameters"
                                 Type="multiString"
                                 Name="AppEnvironmentExtra">
                    <MultiStringValue>ERIC_ADMIN_DIST=[INSTALLDIR]app\admin</MultiStringValue>
                    <MultiStringValue>ERIC_WEB_DIST=[INSTALLDIR]app\web</MultiStringValue>
                    <MultiStringValue>ERIC_PLC_COMPILER=[INSTALLDIR]app\plc\rusty\plc.exe</MultiStringValue>
                    <MultiStringValue>ERIC_DOCS_DIR=[INSTALLDIR]app\docs</MultiStringValue>
                  </RegistryValue>
                  <RegistryValue Root="HKLM"
                                 Key="System\CurrentControlSet\Services\SERVICE_NAME\Parameters"
                                 Type="string"
                                 Name="AppStdout"
                                 Value="[INSTALLDIR]service-stdout.log"/>
                  <RegistryValue Root="HKLM"
                                 Key="System\CurrentControlSet\Services\SERVICE_NAME\Parameters"
                                 Type="string"
                                 Name="AppStderr"
                                 Value="[INSTALLDIR]service-stdout.log"/>
                  <RegistryValue Root="HKLM"
                                 Key="System\CurrentControlSet\Services\SERVICE_NAME\Parameters"
                                 Type="integer"
                                 Name="AppStopMethodConsole"
                                 Value="15000"/>
                  <RegistryValue Root="HKLM" Key="Software\${xml(productName)}" Type="string" Name="InstallDir" Value="[INSTALLDIR]" />
                </Component>
              </DirectoryRef>
              <!-- Every install writes a Windows Installer log (also without /L*v); a copy is kept at the documented path. -->
              <Property Id="MsiLogging" Value="voicewarmupx!" />
              <CustomAction Id="EricKeepInstallLog" Directory="INSTALLDIR" Return="asyncNoWait" Execute="immediate"
                            ExeCommand="&quot;[SystemFolder]cmd.exe&quot; /c if not exist &quot;[CommonAppDataFolder]${xml(productName)}\logs&quot; mkdir &quot;[CommonAppDataFolder]${xml(productName)}\logs&quot; &amp; copy /y &quot;[MsiLogFileLocation]&quot; &quot;[CommonAppDataFolder]${xml(productName)}\logs\install.log&quot;" />
              <InstallExecuteSequence>
                <Custom Action="EricKeepInstallLog" After="InstallFinalize">MsiLogFileLocation AND NOT (REMOVE~="ALL")</Custom>
              </InstallExecuteSequence>
              <!-- Finish page: the address, the shortcut name, and a ticked box that opens the admin page. -->
              <Property Id="WIXUI_EXITDIALOGOPTIONALTEXT" Value="${xml(finishText())}" />
              <Property Id="WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT" Value="Open the admin page now (${xml(adminUrl)})" />
              <Property Id="WIXUI_EXITDIALOGOPTIONALCHECKBOX" Value="1" />
              <CustomAction Id="EricOpenAdmin" Directory="INSTALLDIR" Return="asyncNoWait"
                            ExeCommand="&quot;[INSTALLDIR]${xml(adminLauncherName)}.exe&quot;" />
              <UI>
                <Publish Dialog="ExitDialog" Control="Finish" Event="DoAction" Value="EricOpenAdmin">WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 AND NOT Installed</Publish>
              </UI>
            </Include>
        """.trimIndent() + "\n"
    }

    fun finishText(): String =
        "$gatewayName is installed and runs as a Windows service. Open $adminUrl on this computer to create the " +
            "administrator account (the first account can only be created here). Start menu: $adminLauncherName. " +
            "Install log: $installLog"

    /** jpackage --add-launcher properties for [adminLauncherName]. */
    fun adminLauncherProperties(): String = buildString {
        appendLine("arguments=open-admin")
        appendLine("launcher-as-service=false")
        appendLine("win-console=true")
        appendLine("win-menu=true")
        appendLine("win-shortcut=true")
    }

    fun layoutProperties(version: String): String = buildString {
        appendLine("productName=$productName")
        appendLine("vendor=$vendor")
        appendLine("gatewayName=$gatewayName")
        appendLine("designerName=$designerName")
        appendLine("appVersion=$version")
        appendLine("gatewayInstaller=$gatewayName-$version.exe")
        appendLine("designerInstaller=$designerName-$version.exe")
        appendLine("serviceName=$serviceName")
        appendLine("dataDir=$dataDir")
        appendLine("healthUrl=http://127.0.0.1:8090/api/v1/health")
        appendLine("menuGroup=$menuGroup")
        appendLine("adminLauncherName=$adminLauncherName")
        appendLine("installLog=$installLog")
    }

    fun gatewayImageArgs(version: String, inputDir: String, mainJar: String, destDir: String): List<String> =
        imageArgs(gatewayName, version, inputDir, mainJar, destDir, GATEWAY_MAIN, GATEWAY_MODULES) +
            listOf("--arguments", "open-admin")

    fun designerImageArgs(version: String, inputDir: String, mainJar: String, destDir: String): List<String> =
        imageArgs(designerName, version, inputDir, mainJar, destDir, DESIGNER_MAIN, listOf("java.base"))

    fun gatewayExeArgs(
        version: String,
        inputDir: String,
        mainJar: String,
        destDir: String,
        resourceDir: String,
        upgradeUuid: String,
    ): List<String> = gatewayImageArgs(version, inputDir, mainJar, destDir)
        .map { if (it == "app-image") "exe" else it } + listOf(
        "--win-console",
        "--win-menu",
        "--win-shortcut",
        "--win-menu-group", menuGroup,
        "--win-upgrade-uuid", requireUuid(upgradeUuid),
        "--launcher-as-service",
        "--add-launcher", "$adminLauncherName=$resourceDir/admin-launcher.properties",
        "--resource-dir", resourceDir,
        "--icon", "$resourceDir/eric.ico",
        "--license-file", "$resourceDir/LICENSE-INSTALL.txt",
    )

    fun designerExeArgs(
        version: String,
        inputDir: String,
        mainJar: String,
        destDir: String,
        upgradeUuid: String,
        resourceDir: String,
    ): List<String> = designerImageArgs(version, inputDir, mainJar, destDir)
        .map { if (it == "app-image") "exe" else it } + listOf(
        "--win-console",
        "--win-menu",
        "--win-shortcut",
        "--win-menu-group", menuGroup,
        "--win-upgrade-uuid", requireUuid(upgradeUuid),
        "--resource-dir", resourceDir,
        "--icon", "$resourceDir/eric.ico",
        "--license-file", "$resourceDir/LICENSE-INSTALL.txt",
    )

    private fun imageArgs(
        name: String,
        version: String,
        inputDir: String,
        mainJar: String,
        destDir: String,
        mainClass: String,
        modules: List<String>,
    ): List<String> = listOf(
        "--type", "app-image",
        "--dest", destDir,
        "--name", name,
        "--vendor", vendor,
        "--app-version", version,
        "--description", name,
        "--input", inputDir,
        "--main-jar", mainJar,
        "--main-class", mainClass,
        "--add-modules", modules.joinToString(","),
        "--jlink-options", JLINK_OPTIONS,
    )

    companion object {
        const val DEFAULT_PRODUCT_NAME = "ERIC"
        const val DEFAULT_HTTP_PORT = 8090
        const val GATEWAY_MAIN = "org.oip.gateway.MainKt"
        const val DESIGNER_MAIN = "org.oip.designer.LauncherKt"
        const val JLINK_OPTIONS =
            "--strip-native-commands --strip-debug --no-man-pages --no-header-files --compress zip-6"
        val GATEWAY_MODULES = listOf(
            "java.base",
            "java.compiler",
            "java.instrument",
            "java.logging",
            "java.management",
            "java.naming",
            "java.net.http",
            "java.prefs",
            "java.rmi",
            "java.scripting",
            "java.security.jgss",
            "java.security.sasl",
            "java.sql",
            "java.xml",
            "java.xml.crypto",
            "jdk.charsets",
            "jdk.crypto.cryptoki",
            "jdk.crypto.ec",
            "jdk.httpserver",
            "jdk.management",
            "jdk.naming.dns",
            "jdk.security.auth",
            "jdk.unsupported",
            "jdk.zipfs",
        )
        private const val INVALID_NAME = "\\/:*?\"<>|"

        fun requireUuid(value: String): String {
            UUID.fromString(value)
            return value
        }

        private fun xml(value: String): String = value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
}

internal fun launcherFile(destDir: File, name: String): File {
    val bin = destDir.resolve(name).resolve("bin")
    val win = bin.resolve("$name.exe")
    return if (win.isFile) win else bin.resolve(name)
}
