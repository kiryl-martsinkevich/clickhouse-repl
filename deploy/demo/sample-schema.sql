-- Sample tables matching docs/examples/replicator.yaml, as ReplicatedMergeTree on the "prod" and
-- "DR" replicas of the demo (and of the portal E2E test). Run on both; prod receives the inserts.
CREATE DATABASE IF NOT EXISTS trading;
CREATE DATABASE IF NOT EXISTS crm;
CREATE DATABASE IF NOT EXISTS refdata;

CREATE TABLE IF NOT EXISTS trading.trades (
    trade_id UInt64, trade_date Date, inserted_at DateTime64(3), instrument_id UInt32, customer_id UInt64,
    account_number String, counterparty_name LowCardinality(String), quantity Int64, price Decimal(18, 6),
    notional Decimal(18, 2), trader_comment Nullable(String), tags Array(LowCardinality(String)),
    attributes Map(String, String)
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/trading/trades', '{replica}')
PARTITION BY toYYYYMM(trade_date) ORDER BY trade_id
SETTINGS index_granularity = 1024;

CREATE TABLE IF NOT EXISTS trading.positions (
    cob_date Date, customer_id UInt64, account_number String, instrument_id UInt32, quantity Int64,
    market_value Decimal(18, 2)
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/trading/positions', '{replica}')
PARTITION BY toYYYYMM(cob_date) ORDER BY (customer_id, instrument_id);

CREATE TABLE IF NOT EXISTS crm.customers (
    customer_id UInt64, first_name String, last_name String, email String, phone String,
    date_of_birth Date, onboarded_at DateTime, profile_json String, segment LowCardinality(String)
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/crm/customers', '{replica}')
ORDER BY customer_id;

CREATE TABLE IF NOT EXISTS refdata.instruments (
    instrument_id UInt32, symbol String, name String, asset_class LowCardinality(String), currency FixedString(3)
) ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/refdata/instruments', '{replica}')
ORDER BY instrument_id;
