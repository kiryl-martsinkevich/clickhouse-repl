package chrepl.control

import chrepl.clickhouse.ClickHouseException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DescribeTest {
    @Test
    fun `quoted values in ClickHouse errors are redacted`() {
        val e =
            ClickHouseException(
                27,
                "Code: 27. Cannot parse input: expected ',' before: 'alice@bank.com\\'s',1234 at row 7 in `crm`.`customers`",
            )
        assertEquals(
            "ClickHouseException: Code: 27. Cannot parse input: expected '…' before: '…',1234 at row 7 in `crm`.`customers`",
            Worker.describe(e),
        )
    }

    @Test
    fun `the tool's own messages keep the names they quote`() {
        val e = IllegalStateException("column 'client_ssn' of trading.trades has no masking policy")
        assertEquals("IllegalStateException: column 'client_ssn' of trading.trades has no masking policy", Worker.describe(e))
    }
}
