import init.GatewayModule
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayModuleTest {
    @Test
    fun moduleIdIsStable() {
        assertEquals("gateway", GatewayModule.MODULE_ID)
    }
}
