package chrepl.control

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DescribeTest {
    @Test
    fun `quoted values in error messages are redacted`() {
        val e =
            IllegalStateException(
                "Code: 27. Cannot parse input: expected ',' before: 'alice@bank.com\\'s',1234 at row 7 in `crm`.`customers`",
            )
        assertEquals(
            "IllegalStateException: Code: 27. Cannot parse input: expected '…' before: '…',1234 at row 7 in `crm`.`customers`",
            Worker.describe(e),
        )
    }
}
