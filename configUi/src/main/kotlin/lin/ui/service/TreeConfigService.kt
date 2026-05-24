package lin.ui.service

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.EvaluatorTreeConfig
import lin.tree_config.db.TreeConfigEntity
import lin.tree_config.db.TreeConfigRepository
import lin.utils.json.registerLogicNodeMixin
import java.util.*

fun createTreeConfigMapper(): ObjectMapper {
    return jacksonObjectMapper()
        .registerLogicNodeMixin()
        .addMixIn(EvaluatorPayload::class.java, EvaluatorPayloadMixin::class.java)
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes(
    JsonSubTypes.Type(value = EvaluatorPayload.Rule::class, name = "Rule"),
    JsonSubTypes.Type(value = EvaluatorPayload.BranchCondition::class, name = "BranchCondition")
)
abstract class EvaluatorPayloadMixin

class TreeConfigService(
    private val repository: TreeConfigRepository,
    private val mapper: ObjectMapper
) {


    fun saveConfig(name: String, config: EvaluatorTreeConfig, existingId: String? = null): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        val json = mapper.writeValueAsString(config)
        val entity = TreeConfigEntity(
            id = id,
            groupIds = config.bindGroupIds.joinToString(","),
            name = name,
            configData = json
        )
        repository.save(entity)
        return id
    }

    fun loadAll(): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findAll().map { entity ->
            val config = try {
                mapper.readValue(entity.configData, EvaluatorTreeConfig::class.java)
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
            entity to config
        }
    }

    fun delete(id: String) {
        repository.deleteById(id)
    }
}
