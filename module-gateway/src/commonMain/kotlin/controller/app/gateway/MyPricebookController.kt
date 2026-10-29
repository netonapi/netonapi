package controller.app.gateway

import logic.PricebookGroupVO
import logic.PricebookView
import neton.core.annotations.Controller
import neton.core.annotations.Get
import neton.core.interfaces.Identity

/**
 * 我的价目表（spec §4.3）：我在自己 Key 所在的每个计费分组下能用哪些模型、各多少钱、相当于官方价几折、
 * 是不是协议价。与转发、`/v1/models` 同一个查价口径——看得到的就是调得通的，价格就是会扣的。
 */
@Controller("/gateway/pricebook")
class MyPricebookController {

    @Get("/mine")
    suspend fun mine(identity: Identity): List<PricebookGroupVO> {
        val userId = identity.id.toLong()
        return PricebookView.build(userId, PricebookView.groupsOf(userId))
    }
}
