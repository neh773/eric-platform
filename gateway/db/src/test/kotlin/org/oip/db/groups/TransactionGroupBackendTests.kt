package org.oip.db.groups

class TransactionGroupSqliteTest : TransactionGroupContract(SqliteBackend)

/**
 * Same contract on the compose Timescale service (`test-infra/compose.yaml`, port `OIP_SIM_POSTGRES_PORT`,
 * default 25432). The test does not start it; when nothing answers, every case is skipped by an assumption
 * in [TransactionGroupContract.open].
 */
class TransactionGroupPostgresTest : TransactionGroupContract(PostgresBackend)
