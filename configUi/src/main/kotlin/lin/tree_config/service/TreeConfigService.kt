package lin.tree_config.service

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.tree.EvaluatorNode
import lin.rule.tree.EvaluatorTreeConfig
import lin.tree_config.domain.TreeConfigEntity
import lin.tree_config.repository.TreeConfigRepository
import java.util.*

// 采用 WRAPPER_OBJECT 模式，使得序列化后的 JSON 结构为 {"RuleNode": {...}} 而非 {"type": "RuleNode", ...}
// 这种格式对 AI 生成配置更友好，同时与人工手写 JSON 的习惯更接近
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
@JsonSubTypes(
    JsonSubTypes.Type(value = EvaluatorNode.RuleNode::class, name = "RuleNode"),
    JsonSubTypes.Type(value = EvaluatorNode.AndNode::class, name = "AndNode"),
    JsonSubTypes.Type(value = EvaluatorNode.OrNode::class, name = "OrNode"),
    JsonSubTypes.Type(value = EvaluatorNode.NotNode::class, name = "NotNode"),
    JsonSubTypes.Type(value = EvaluatorNode.BranchNode::class, name = "BranchNode")
)
abstract class EvaluatorNodeMixin

class TreeConfigService(private val repository: TreeConfigRepository) {

    val mapper: ObjectMapper = jacksonObjectMapper()
        .addMixIn(EvaluatorNode::class.java, EvaluatorNodeMixin::class.java)


    fun saveConfig(name: String, config: EvaluatorTreeConfig, existingId: String? = null): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        val json = mapper.writeValueAsString(config)
        val entity = TreeConfigEntity(
            id = id,
            //todo 准备改成多个,暂时用toString解决报错
            groupId = config.bindGroupIds.toString(),
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
