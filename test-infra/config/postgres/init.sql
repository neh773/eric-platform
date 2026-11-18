-- Test database. Credentials are oip/oip and exist only inside this simulator.
CREATE EXTENSION IF NOT EXISTS timescaledb;
CREATE TABLE IF NOT EXISTS sim_samples (
    ts TIMESTAMPTZ NOT NULL,
    signal TEXT NOT NULL,
    value DOUBLE PRECISION NOT NULL
);
SELECT create_hypertable('sim_samples', 'ts', if_not_exists => TRUE);
