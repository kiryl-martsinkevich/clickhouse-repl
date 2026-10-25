package chrepl.config

import chrepl.config.load.ConfigLoader
import chrepl.config.model.ColumnPolicy
import chrepl.config.model.ShardMapping
import chrepl.config.model.Strategy
import chrepl.config.validate.ValidationOptions
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ExampleConfigTest {
    @Test
    fun `example config validates without errors or warnings`() {
        Fixtures.check(Fixtures.exampleTree()).assertClean()
    }

    @Test
    fun `example config validates with DNS checks when every host resolves outside the active DC`() {
        Fixtures.check(Fixtures.exampleTree(), ValidationOptions(resolveDns = true, hostResolver = Fixtures.resolver())).assertClean()
    }

    @Test
    fun `example config binds to the model`() {
        val config = assertNotNull(ConfigLoader.load(Fixtures.examplePath).config)
        val dr = config.sources.getValue("dr")
        assertEquals(3, dr.shards.size)
        assertEquals("ch-dr-s1.dc-dr.internal", dr.shards[0].host.host)
        assertEquals(8443, dr.shards[0].host.port)
        assertEquals(ShardMapping.SAME_SHARDS, config.targets.getValue("qa1").shardMapping)
        assertEquals(
            Strategy.FPE_FF1,
            config.masking.domains
                .getValue("customer_id")
                .strategy,
        )
        assertEquals(60, config.guard.stableWindow.value.seconds)

        val trades = config.tables.first { it.source == "trading.trades" }
        assertEquals(ColumnPolicy.Pass, trades.columns["trade_id"])
        assertEquals(ColumnPolicy.Mask("amount_noise", "customer_id"), trades.columns["notional"])
        val attributes = assertIs<ColumnPolicy.MapPolicy>(trades.columns["attributes"])
        assertEquals(ColumnPolicy.Mask("email"), attributes.mapValues["client_email"])
        val customers = config.tables.first { it.source == "crm.customers" }
        assertIs<ColumnPolicy.JsonPaths>(customers.columns["profile_json"])
    }
}
