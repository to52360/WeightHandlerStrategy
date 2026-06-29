package lin.ui.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.condition.ConditionPayload
import lin.rule.score.ScoreEffect
import lin.rule.tree.*
import lin.ui.tree_config.db.EvaluatorLeafConfigRepository
import lin.ui.tree_config.db.TreeConfigEntity
import lin.ui.tree_config.db.TreeConfigRepository
import lin.utils.json.registerLogicNodeMixin
import java.util.*

fun createTreeConfigMapper(): ObjectMapper {
    return jacksonObjectMapper()
        .registerLogicNodeMixin()
        // ── 评估树节点 ──
        .apply {
            addMixIn(EvaluatorPayload::class.java, EvaluatorPayloadMixin::class.java)
            registerSubtypes(
                NamedType(EvaluatorPayload.Rule::class.java, "Rule"),
                NamedType(EvaluatorPayload.BranchCondition::class.java, "BranchCondition")
            )
        }
        // ── 评分效应 ──
        .apply {
            addMixIn(ScoreEffect::class.java, ScoreEffectMixin::class.java)
            registerSubtypes(
                NamedType(ScoreEffect.ConstantScore::class.java, "ConstantScore"),
                NamedType(ScoreEffect.SourceScore::class.java, "SourceScore")
            )
        }
        // ── 条件节点 ──
        .apply {
            addMixIn(ConditionPayload::class.java, ConditionPayloadMixin::class.java)
            registerSubtypes(
                NamedType(ConditionPayload.ConditionRef::class.java, "ConditionRef"),
                NamedType(ConditionPayload.PipelineRef::class.java, "PipelineRef")
            )
        }
        // ── 叶子节点配置 ──
        .apply {
            addMixIn(EvaluatorLeafConfig::class.java, EvaluatorLeafConfigMixin::class.java)
            registerSubtypes(
                NamedType(RuleLeafConfig::class.java, "RULE"),
                NamedType(OrthogonalRuleLeafConfig::class.java, "ORTHOGONAL_RULE"),
                NamedType(OrthogonalConditionLeafConfig::class.java, "ORTHOGONAL_CONDITION"),
                NamedType(ConditionLeafConfig::class.java, "CONDITION"),
                NamedType(ConditionTreeLeafConfig::class.java, "CONDITION_TREE")
            )
        }
}



@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class EvaluatorPayloadMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class ScoreEffectMixin

class TreeConfigService(
    private val repository: TreeConfigRepository,
    private val leafConfigRepository: EvaluatorLeafConfigRepository,
    private val mapper: ObjectMapper
) {
    /**
     * 避免 EvaluatorNode (typealias) 导致的 Jackson 泛型解析丢失。
     * 显式声明 LogicNode&lt;EvaluatorPayload&gt; 使 JsonTypeInfo 正确处理 payload 多态。
     */
    private data class RootHolder(val root: LogicNode<EvaluatorPayload>)

    fun saveConfig(
        name: String,
        config: EvaluatorTreeConfig,
        existingId: String? = null,
        enabled: Boolean = true,
        managerId: String? = null,
        description: String? = null
    ): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)

        // 仅序列化 root（树结构），leafConfigs 走独立表。
        // 用 RootHolder 包装以绕过 typealias 的泛型解析丢失问题。
        val rootJson = mapper.writeValueAsString(RootHolder(config.root))
        val entity = TreeConfigEntity(
            id = id,
            bindingType = config.bindingType.name,
            bindingIds = config.bindingIds.joinToString(","),
            name = name,
            description = description,
            configData = rootJson,
            enabled = enabled,
            managerId = managerId
        )
        repository.save(entity)
        leafConfigRepository.saveAll(id, config.leafConfigs, mapper)
        return id
    }

    fun loadAll(): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findAll().map { entity ->
            entity to parseConfig(entity)
        }
    }

    fun countConfigs(bindingType: String? = null): Int {
        return repository.countAll(bindingType)
    }

    fun loadPage(
        offset: Int,
        limit: Int,
        bindingType: String? = null
    ): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findPage(offset, limit, bindingType).map { entity ->
            entity to parseConfig(entity)
        }
    }

    fun findById(id: String): Pair<TreeConfigEntity, EvaluatorTreeConfig?>? {
        val entity = repository.findById(id) ?: return null
        return entity to parseConfig(entity)
    }

    private fun parseConfig(entity: TreeConfigEntity): EvaluatorTreeConfig? {
        val root = try {
            mapper.readValue(entity.configData, RootHolder::class.java).root
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        val leafConfigs = leafConfigRepository.findByConfigId(entity.id, mapper)
        return EvaluatorTreeConfig(
            bindingType = EvaluatorTreeBindingType.valueOf(entity.bindingType),
            bindingIds = entity.bindingIds.split(",").filter { it.isNotBlank() },
            root = root,
            leafConfigs = leafConfigs
        )
    }

    fun delete(id: String) {
        leafConfigRepository.deleteByConfigId(id)
        repository.deleteById(id)
    }

    /** 按 managerId 加载配置（包含全局共享） */
    fun loadByManagerId(managerId: String?): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findByManagerId(managerId, includeGlobal = true).map { entity ->
            entity to parseConfig(entity)
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class ConditionPayloadMixin

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.WRAPPER_OBJECT
)
@JsonIgnoreProperties(ignoreUnknown = true)
abstract class EvaluatorLeafConfigMixin



