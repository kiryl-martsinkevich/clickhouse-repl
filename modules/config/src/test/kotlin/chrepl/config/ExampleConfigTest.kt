package chrepl.config

import chrepl.config.load.ConfigLoader
import chrepl.config.model.ColumnPolicy
import chrepl.config.model.FailClosedScope
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
        assertEquals(1, dr.shards.size)
        assertEquals(3, dr.shards[0].hosts.size)
        assertEquals("ch-dr-1.dc-dr.internal", dr.shards[0].hosts[0].host)
        assertEquals(8443, dr.shards[0].hosts[0].port)
        assertEquals(FailClosedScope.REPLICA, dr.failClosedScope)
        assertEquals(9181, dr.keeper!!.hosts[0].port)
        val qa1 = config.targets.getValue("qa1")
        assertEquals(ShardMapping.SINGLE, qa1.shardMapping)
        assertEquals(3, qa1.replicas)
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
