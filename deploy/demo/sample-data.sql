-- Fictional sample data for three COB dates (2026-10-01 .. 2026-10-03). Insert on "prod" only.
INSERT INTO trading.trades
SELECT
    number AS trade_id,
    toDate('2026-10-01') + intDiv(number, 2000) AS trade_date,
    toDateTime64('2026-10-01 08:00:00', 3) + number AS inserted_at,
    number % 500 AS instrument_id,
    100000 + number % 2000 AS customer_id,
    concat('GB29NWBK6016', leftPad(toString(number % 2000), 8, '0')) AS account_number,
    ['Northwind Capital', 'Cobalt Capital', 'Meridian Group'][1 + number % 3] AS counterparty_name,
    toInt64(number % 1000) - 500 AS quantity,
    toDecimal64(101.25, 6) AS price,
    toDecimal64(number % 100000, 2) / 100 AS notional,
    if(number % 4 = 0, concat('call client ', toString(100000 + number % 2000)), NULL) AS trader_comment,
    ['fx', 'spot'] AS tags,
    map('client_email', concat('client', toString(number % 2000), '@bank.com'), 'desk', 'rates') AS attributes
FROM numbers(6000);

INSERT INTO trading.positions
SELECT
    toDate('2026-10-01') + intDiv(number, 1000) AS cob_date,
    100000 + number % 2000 AS customer_id,
    concat('GB29NWBK6016', leftPad(toString(number % 2000), 8, '0')) AS account_number,
    number % 500 AS instrument_id,
    toInt64(number % 300) * 10 AS quantity,
    toDecimal64(number % 50000, 2) AS market_value
FROM numbers(3000);

INSERT INTO crm.customers
SELECT
    100000 + number AS customer_id,
    ['Olivia', 'Liam', 'Amelia', 'Noah', 'Isla'][1 + number % 5] AS first_name,
    ['Smith', 'Jones', 'Taylor', 'Brown', 'Wilson'][1 + number % 5] AS last_name,
    concat('client', toString(number), '@bank.com') AS email,
    concat('+44 20 7946 ', leftPad(toString(number % 10000), 4, '0')) AS phone,
    toDate('1960-01-01') + number * 7 AS date_of_birth,
    toDateTime('2020-01-01 09:00:00') + number * 3600 AS onboarded_at,
    concat('{"contact":{"email":"client', toString(number), '@bank.com","phone":"+44 20 7946 0000"},"notes":"vip"}') AS profile_json,
    ['retail', 'private', 'corporate'][1 + number % 3] AS segment
FROM numbers(2000);

INSERT INTO refdata.instruments
SELECT
    number AS instrument_id,
    concat('SYM', toString(number)) AS symbol,
    concat('Instrument ', toString(number)) AS name,
    ['equity', 'bond', 'fx'][1 + number % 3] AS asset_class,
    ['GBP', 'USD', 'EUR'][1 + number % 3] AS currency
FROM numbers(500);
