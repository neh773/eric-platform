package org.oip.gateway

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.io.path.writeText

/** 0.1.1: turning HTTPS on or off on the Network page applies to the running gateway, without a restart. */
class HttpsLiveApplyTest {
    @TempDir lateinit var tmp: Path

    @Test fun httpsTurnsOnAndOffWithoutARestart() {
        Files.createDirectories(tmp)
        tmp.resolve("gateway.yaml").writeText(
            "profile: standard\nhttp:\n  host: 127.0.0.1\nopcua:\n  enabled: false\ngatewayNetwork:\n  enabled: false\n",
        )
        Gateway(tmp, port = 0).use { gateway ->
            gateway.start()
            val http = Http(gateway.port())
            gateway.loginAdmin(http)
            assertNull(gateway.https.listenPort())

            // The Network form sends every field; unchanged HTTP fields must not force a restart.
            val on = http.put("/api/v1/https/settings", Json.write(mapOf(
                "httpPort" to gateway.port(), "httpsEnabled" to true, "httpsPort" to 0,
                "publicAddress" to null, "publicHttpsPort" to null, "forceHttpsRedirect" to false,
            )))
            assertEquals(200, on.status, on.body)
            val saved = on.json["savedSettings"] as Map<*, *>
            assertEquals(true, saved["appliedNow"], on.body)
            assertEquals(false, saved["restartRequired"])
            assertEquals(true, on.json["enabled"])
            val port = gateway.https.listenPort() ?: error("HTTPS is not listening after it was turned on")
            assertEquals(200, httpsGet(port, "/api/v1/health"))

            val off = http.put("/api/v1/https/settings", Json.write(mapOf("httpsEnabled" to false)))
            assertEquals(200, off.status, off.body)
            assertEquals(true, (off.json["savedSettings"] as Map<*, *>)["appliedNow"])
            assertNull(gateway.https.listenPort())
            assertThrows(java.io.IOException::class.java) { httpsGet(port, "/api/v1/health") }

            // An HTTP port change still waits for the next start.
            val later = http.put("/api/v1/https/settings", Json.write(mapOf("httpPort" to 18999, "httpsEnabled" to true, "httpsPort" to 0)))
            assertEquals(true, (later.json["savedSettings"] as Map<*, *>)["restartRequired"])
            assertNull(gateway.https.listenPort())
        }
    }

    private fun httpsGet(port: Int, path: String): Int {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        })
        val context = SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
        val connection = URI("https://127.0.0.1:$port$path").toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory = context.socketFactory
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        return try { connection.responseCode } finally { connection.disconnect() }
    }
}
