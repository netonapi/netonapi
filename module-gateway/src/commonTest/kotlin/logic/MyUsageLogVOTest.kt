package logic

import controller.app.gateway.MyUsageLogVO
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.UsageLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MyUsageLogVOTest {

    private val log = UsageLog(
        id = 7, userId = 1, tokenId = 2, channelId = 99, requestModel = "gpt-4o-mini", upstreamModel = "gpt-4o-mini-2024",
        promptTokens = 1200, completionTokens = 300, cacheReadTokens = 0, cacheWriteTokens = 0,
        charged = 360, cost = 216, groupCode = "default", groupSource = "default", ttfbMs = 10, latencyMs = 20, stream = 1,
    )

    @Test fun userFacingLogHidesCostAndRouting() {
        val json = Json.encodeToString(MyUsageLogVO.of(log))
        for (secret in listOf("cost", "channelId", "upstreamModel", "groupSource")) assertFalse("\"$secret\"" in json, "$secret leaked: $json")
    }

    @Test fun userFacingLogKeepsWhatTheUserPaysFor() {
        val vo = MyUsageLogVO.of(log)
        assertEquals("gpt-4o-mini", vo.requestModel)
        assertEquals(1200, vo.promptTokens)
        assertEquals(300, vo.completionTokens)
        assertEquals(360, vo.charged)
        assertEquals(2, vo.tokenId)
    }
}
