package chrepl.control

import chrepl.config.load.ConfigLoader
import chrepl.config.validate.HostResolver
import chrepl.guard.InMemoryOpsFlags
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveDcWatchTest {
    private val config = ConfigLoader.load(Path.of(System.getProperty("chrepl.exampleConfig"))).config!!

    private fun resolver(vararg overrides: Pair<String, String>) =
        HostResolver { host ->
            val ip = overrides.toMap()[host] ?: "10.20.1.${host.length}" // DR / non-prod addresses by default
            listOf(InetAddress.getByName(ip))
        }

    @Test
    fun `hosts outside the active DC leave the flags alone`() {
        val flags = InMemoryOpsFlags()
        assertTrue(ActiveDcWatch({ config }, flags, resolver()).check().isEmpty())
        assertNull(flags.failover)
    }

    @Test
    fun `a target host that starts resolving into the active DC trips the failover flag`() {
        val flags = InMemoryOpsFlags()
        val violations = ActiveDcWatch({ config }, flags, resolver("ch-qa1-s1.nonprod.internal" to "10.10.4.2")).check()
        assertEquals("ACTIVE_DC_CIDR", violations.single().code.name)
        val reason = assertNotNull(flags.failover)
        assertEquals("ACTIVE_DC", reason.code)
    }
}
