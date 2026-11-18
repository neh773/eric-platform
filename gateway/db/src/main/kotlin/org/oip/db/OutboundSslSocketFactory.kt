package org.oip.db

import org.oip.config.OutboundTls
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * The PostgreSQL driver's TLS socket factory when a connection asks for a verified TLS connection (SEC-022): it opens
 * sockets with the gateway's outbound trust (the JVM's public authorities plus the outbound trust store), so a
 * database server whose certificate is not trusted is refused and listed as rejected for client `database`. The
 * driver makes it by class name, so it has a public no-argument constructor; it reads the trust at each connection.
 *
 * Doc source: FEATURES SEC-022.
 */
class OutboundSslSocketFactory : SSLSocketFactory() {
    private fun factory(): SSLSocketFactory = OutboundTls.context(CLIENT).socketFactory

    override fun getDefaultCipherSuites(): Array<String> = factory().defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = factory().supportedCipherSuites
    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket = factory().createSocket(s, host, port, autoClose)
    override fun createSocket(host: String, port: Int): Socket = factory().createSocket(host, port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = factory().createSocket(host, port, localHost, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = factory().createSocket(host, port)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        factory().createSocket(address, port, localAddress, localPort)

    companion object {
        const val CLIENT = "database"
        private val VERIFYING = setOf("require", "verify-ca", "verify-full")

        /**
         * The `sslfactory` to give a PostgreSQL connection, or null to leave the driver's own. Only a connection that
         * asks for a verified TLS connection (`sslmode` require, verify-ca or verify-full, or `ssl=true`) gets this
         * factory, and only when it names no factory of its own. `prefer` and `allow` are left alone: they fall back
         * to plain text when TLS fails, so refusing a certificate there would silently drop encryption.
         */
        fun forPostgres(url: String, extra: Map<String, String>): String? {
            val params = LinkedHashMap<String, String>()
            url.substringAfter('?', "").split('&').filter { it.contains('=') }.forEach {
                params[it.substringBefore('=').lowercase()] = java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
            for ((k, v) in extra) params[k.lowercase()] = v
            if (params.containsKey("sslfactory")) return null
            val mode = params["sslmode"]?.lowercase()
            val asks = if (mode != null) mode in VERIFYING else params["ssl"]?.equals("true", ignoreCase = true) == true
            return if (asks) OutboundSslSocketFactory::class.java.name else null
        }
    }
}
