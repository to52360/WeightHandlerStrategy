package lin.condition_tree.db

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionTreeConfig
import lin.utils.json.registerLogicNodeMixin
import java.util.*

fun createConditionTreeConfigMapper(): ObjectMapper {
    return jacksonObjectMapper()
        .registerLogicNodeMixin()
        .addMixIn(ConditionPayload::class.java, ConditionPayloadMixin::class.java)
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes(
    JsonSubTypes.Type(value = ConditionPayload.ConditionRef::class, name = "ConditionRef")
)
abstract class ConditionPayloadMixin

class ConditionTreeConfigService(
    private val repository: ConditionTreeConfigRepository,
    private val mapper: ObjectMapper
) {
    fun saveConfig(name: String, config: ConditionTreeConfig, existingId: String? = null): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        val json = mapper.writeValueAsString(config.copy(id = id, name = name))
        repository.save(
            ConditionTreeConfigEntity(
                id = id,
                name = name,
                configData = json
            )
        )
        return id
    }

    fun loadAll(): List<Pair<ConditionTreeConfigEntity, ConditionTreeConfig?>> {
        return repository.findAll().map { entity ->
            entity to readConfig(entity)
        }
    }

    fun findById(id: String): ConditionTreeConfig? {
        val entity = repository.findById(id) ?: return null
        return readConfig(entity)
    }

    fun delete(id: String) {
        repository.deleteById(id)
    }

    fun loadAllMeta(): List<Pair<String, String>> {
        return repository.findAllMeta()
    }

    private fun readConfig(entity: ConditionTreeConfigEntity): ConditionTreeConfig? {
        return try {
            mapper.readValue(entity.configData, ConditionTreeConfig::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
