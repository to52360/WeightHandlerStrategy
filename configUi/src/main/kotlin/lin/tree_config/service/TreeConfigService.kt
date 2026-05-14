package lin.tree_config.service

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.LogicNode
import lin.rule.tree.TreeConfigProvider
import lin.tree_config.domain.TreeConfigEntity
import lin.tree_config.repository.TreeConfigRepository
import lin.utils.resolveJdbcProvider
import java.util.*

fun createTreeConfigMapper(): ObjectMapper {
    return jacksonObjectMapper()
        .addMixIn(LogicNode::class.java, EvaluatorNodeMixin::class.java)
        .addMixIn(EvaluatorPayload::class.java, EvaluatorPayloadMixin::class.java)
}

// 采用 WRAPPER_OBJECT 模式，使得序列化后的 JSON 结构为 {"AndNode": {...}} 而非 {"type": "AndNode", ...}
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes(
    JsonSubTypes.Type(value = LogicNode.Leaf::class, name = "Leaf"),
    JsonSubTypes.Type(value = LogicNode.And::class, name = "AndNode"),
    JsonSubTypes.Type(value = LogicNode.Or::class, name = "OrNode"),
    JsonSubTypes.Type(value = LogicNode.Not::class, name = "NotNode"),
    JsonSubTypes.Type(value = LogicNode.Branch::class, name = "BranchNode")
)
abstract class EvaluatorNodeMixin

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

class SqliteTreeConfigProvider(
    private val repository: TreeConfigRepository,
    private val mapper: ObjectMapper
) : TreeConfigProvider {
    constructor() : this(
        TreeConfigRepository(resolveJdbcProvider().jdbcTemplate),
        createTreeConfigMapper()
    )

    override fun findById(id: String): EvaluatorTreeConfig? {
        val entity = repository.findById(id) ?: return null
        return runCatching {
            mapper.readValue(entity.configData, EvaluatorTreeConfig::class.java)
        }.getOrNull()
    }

    override fun findAll(): List<EvaluatorTreeConfig> {
        return repository.findAll().mapNotNull { entity ->
            runCatching {
                mapper.readValue(entity.configData, EvaluatorTreeConfig::class.java)
            }.getOrNull()
        }
    }
}
