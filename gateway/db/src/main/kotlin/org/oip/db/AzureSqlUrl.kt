package org.oip.db

/**
 * Azure SQL connections (doc source: FEATURES DAT-020). The `azuresql` driver is the SQL Server dialect over the
 * bundled SQL Server JDBC driver, with the settings Azure requires: an encrypted channel and a verified server
 * certificate. The URL the admin saves is kept as written; [resolve] adds the missing settings for the pool.
 */
internal object AzureSqlUrl {
    private const val PREFIX = "jdbc:sqlserver://"
    private const val AZURE_HOST_SUFFIX = ".database.windows.net"
    private const val DEFAULT_LOGIN_TIMEOUT_SECONDS = "30"

    /** Refuse a URL that weakens what Azure needs. Called when a connection is saved. */
    fun check(url: String) {
        if (!url.startsWith(PREFIX)) throw QueryRejected("azuresql url must start with $PREFIX")
        val props = properties(url)
        props["encrypt"]?.let { if (!it.equals("true", true) && !it.equals("strict", true)) throw QueryRejected("azuresql requires encrypt=true") }
        props["trustservercertificate"]?.let { if (!it.equals("false", true)) throw QueryRejected("azuresql requires trustServerCertificate=false") }
    }

    /** The URL the driver opens: encryption on, the certificate checked, a login timeout, and the Azure certificate host name. */
    fun resolve(url: String): String {
        check(url)
        val props = properties(url)
        val added = StringBuilder()
        fun add(key: String, value: String) { if (key.lowercase() !in props) added.append(';').append(key).append('=').append(value) }
        add("encrypt", "true")
        add("trustServerCertificate", "false")
        if (host(url).endsWith(AZURE_HOST_SUFFIX, ignoreCase = true)) add("hostNameInCertificate", "*$AZURE_HOST_SUFFIX")
        add("loginTimeout", DEFAULT_LOGIN_TIMEOUT_SECONDS)
        return url.trimEnd(';') + added
    }

    /** The server host name, without port or instance. */
    fun host(url: String): String = url.removePrefix(PREFIX).substringBefore(';').substringBefore(':').substringBefore('\\')

    /** `key=value` settings after the server part, lower-cased keys. */
    private fun properties(url: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in url.removePrefix(PREFIX).split(';').drop(1)) {
            val eq = part.indexOf('=')
            if (eq > 0) out[part.substring(0, eq).trim().lowercase()] = part.substring(eq + 1).trim()
        }
        return out
    }
}
