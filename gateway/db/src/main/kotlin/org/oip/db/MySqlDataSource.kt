package org.oip.db

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Opens MariaDB and MySQL connections with the bundled BSD-licensed client (DAT-003, DAT-019, DAT-022).
 *
 * That client reads the isolation level from `@@tx_isolation`, which MySQL 8 removed, and the pool treats the
 * resulting error as a failed connection. The connection handed out here answers `getTransactionIsolation`
 * from `@@transaction_isolation` (or the old name) and passes every other call through.
 */
internal class MySqlDataSource(private val url: String, private val user: String?, private val password: String?) : DataSource {
    private var loginTimeout = 0

    override fun getConnection(): Connection = open(user, password)

    override fun getConnection(username: String?, password: String?): Connection = open(username, password)

    private fun open(username: String?, secret: String?): Connection {
        val props = Properties()
        if (!username.isNullOrEmpty()) props.setProperty("user", username)
        if (!secret.isNullOrEmpty()) props.setProperty("password", secret)
        val raw = org.drizzle.jdbc.DrizzleDriver().connect(url, props) ?: throw SQLException("not a MySQL or MariaDB url")
        return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            try {
                if (method.name == "getTransactionIsolation" && method.parameterCount == 0) isolation(raw)
                else method.invoke(raw, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Connection
    }

    private fun isolation(connection: Connection): Int {
        for (variable in listOf("@@transaction_isolation", "@@tx_isolation")) {
            val level = try {
                connection.createStatement().use { st -> st.executeQuery("SELECT $variable").use { rs -> if (rs.next()) rs.getString(1) else null } }
            } catch (e: SQLException) {
                null
            }
            when (level?.uppercase()?.replace('_', '-')) {
                "READ-UNCOMMITTED" -> return Connection.TRANSACTION_READ_UNCOMMITTED
                "READ-COMMITTED" -> return Connection.TRANSACTION_READ_COMMITTED
                "REPEATABLE-READ" -> return Connection.TRANSACTION_REPEATABLE_READ
                "SERIALIZABLE" -> return Connection.TRANSACTION_SERIALIZABLE
            }
        }
        return Connection.TRANSACTION_REPEATABLE_READ
    }

    override fun getLogWriter(): java.io.PrintWriter? = null
    override fun setLogWriter(out: java.io.PrintWriter?) {}
    override fun setLoginTimeout(seconds: Int) { loginTimeout = seconds }
    override fun getLoginTimeout(): Int = loginTimeout
    override fun getParentLogger(): Logger = Logger.getLogger("org.oip.db")
    override fun <T : Any?> unwrap(iface: Class<T>): T = if (iface.isInstance(this)) iface.cast(this) else throw SQLException("not a wrapper")
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}
