package org.oip.config

import javax.net.ssl.SSLContext

/**
 * Where the gateway's outbound TLS clients get their trust (SEC-022). The gateway installs its outbound trust store
 * at start ([install]); a client asks [context] with its own name (shown beside a certificate it refuses) when it
 * opens a TLS connection. Without an installed store, [context] is the JVM default.
 *
 * The installed store also becomes the JVM's default SSL context while it is installed, so a client this gateway
 * does not name (a JDBC driver that uses the default TLS factory, for one) gets the same trust.
 *
 * Doc source: FEATURES SEC-022.
 */
object OutboundTls {
    fun interface Provider {
        fun context(client: String): SSLContext
    }

    @Volatile private var provider: Provider? = null
    @Volatile private var previousDefault: SSLContext? = null

    /** The SSL context for an outbound connection by [client] (for example `mqtt`, `http`, `mail`). */
    fun context(client: String): SSLContext = provider?.let { runCatching { it.context(client) }.getOrNull() } ?: SSLContext.getDefault()

    /** Install [next]; returns a handle that removes it again (only while it is still the installed one). */
    @Synchronized
    fun install(next: Provider): AutoCloseable {
        if (provider == null) previousDefault = SSLContext.getDefault()
        provider = next
        runCatching { SSLContext.setDefault(next.context("jvm")) }
        return AutoCloseable { uninstall(next) }
    }

    @Synchronized
    private fun uninstall(installed: Provider) {
        if (provider !== installed) return
        provider = null
        previousDefault?.let { runCatching { SSLContext.setDefault(it) } }
        previousDefault = null
    }
}
