package controller.admin.group

import kotlinx.serialization.Serializable
import model.GatewayGroup
import neton.core.annotations.*
import table.GatewayGroupTable

@Serializable
data class SaveGroupRequest(
    val id: Long = 0,
    val name: String,
    val ratio: String = "1.0",
    val description: String? = null,
)

@Controller("/gateway/group")
class GroupController {

    @Get("/list")
    @Permission("gateway:group:list")
    suspend fun list(): List<GatewayGroup> = GatewayGroupTable.findAll()

    @Get("/get/{id}")
    @Permission("gateway:group:get")
    suspend fun get(@PathVariable id: Long): GatewayGroup? = GatewayGroupTable.get(id)

    @Post("/create")
    @Permission("gateway:group:create")
    suspend fun create(@Body request: SaveGroupRequest): Long =
        GatewayGroupTable.insert(GatewayGroup(name = request.name, ratio = request.ratio, description = request.description)).id

    @Put("/update")
    @Permission("gateway:group:update")
    suspend fun update(@Body request: SaveGroupRequest) {
        val current = GatewayGroupTable.get(request.id)
            ?: throw IllegalArgumentException("gateway group id=${request.id} not found")
        GatewayGroupTable.update(current.copy(name = request.name, ratio = request.ratio, description = request.description))
    }

    @Delete("/delete/{id}")
    @Permission("gateway:group:delete")
    suspend fun delete(@PathVariable id: Long) { GatewayGroupTable.destroy(id) }
}
