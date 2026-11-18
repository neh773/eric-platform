package org.oip.db.groups

import org.oip.db.DatabaseService
import org.oip.db.QueryFailed
import org.oip.db.QueryNotFound
import org.oip.db.QueryRejected
import org.oip.db.QueryUnavailable
import java.sql.Connection

/**
 * [GroupDatabase] backed by the gateway's [DatabaseService]: a group run borrows a connection from the
 * shared pool, with the service's failover, connection status, and password redaction.
 */
class PooledConnections(private val service: DatabaseService) : GroupDatabase {
    override fun <T> withConnection(connection: String, block: (Connection) -> T): T = try {
        service.withConnection(connection, block)
    } catch (e: QueryNotFound) {
        throw GroupDatabaseUnavailable(e.message ?: "database connection not found")
    } catch (e: QueryRejected) {
        throw GroupDatabaseUnavailable(e.message ?: "database connection rejected")
    } catch (e: QueryUnavailable) {
        throw GroupDatabaseUnavailable(e.message ?: "database unavailable")
    } catch (e: QueryFailed) {
        throw GroupRunFailed(e.message ?: "database error")
    }
}
