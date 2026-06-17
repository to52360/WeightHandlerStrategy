package lin.ui.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.condition.ConditionPayload
import lin.rule.score.ScoreEffect
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
        .addMixIn(ScoreEffect::class.java, ScoreEffectMixin::class.java)
        .addMixIn(ConditionPayload::class.java, ConditionPayloadMixin::class.java)
        .addMixIn(lin.rule.tree.RulePayload::class.java, RulePayloadMixin::class.java)
        .addMixIn(lin.rule.tree.EvaluatorLeafConfig::class.java, EvaluatorLeafConfigMixin::class.java)
        .apply {
            registerSubtypes(
                NamedType(EvaluatorPayload.Rule::class.java, "Rule"),
                NamedType(EvaluatorPayload.BranchCondition::class.java, "BranchCondition"),
                NamedType(ScoreEffect.ConstantScore::class.java, "ConstantScore"),
                NamedType(ScoreEffect.SourceScore::class.java, "SourceScore"),
                NamedType(ConditionPayload.ConditionRef::class.java, "ConditionRef"),
                NamedType(ConditionPayload.OrthogonalRef::class.java, "OrthogonalRef"),
                NamedType(lin.rule.tree.RulePayload.RuleRef::class.java, "RuleRef"),
                NamedType(lin.rule.tree.RulePayload.OrthogonalRuleRef::class.java, "OrthogonalRuleRef"),
                NamedType(lin.rule.tree.RuleLeafConfig::class.java, "RULE"),
                NamedType(lin.rule.tree.OrthogonalRuleLeafConfig::class.java, "ORTHOGONAL_RULE"),
                NamedType(lin.rule.tree.ConditionLeafConfig::class.java, "CONDITION"),
                NamedType(lin.rule.tree.ConditionTreeLeafConfig::class.java, "CONDITION_TREE")
            )
        }
}



@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class EvaluatorPayloadMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class ScoreEffectMixin

class TreeConfigService(
    private val repository: TreeConfigRepository,
    private val mapper: ObjectMapper
) {


    fun saveConfig(
        name: String,
        config: EvaluatorTreeConfig,
        existingId: String? = null,
        enabled: Boolean = true,
        managerId: String? = null,
        isTemplate: Boolean = false
    ): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        val json = mapper.writeValueAsString(config)
        val bindingType = config.bindings.firstOrNull()?.type?.name ?: "UNKNOWN"
        val bindingsSummary = config.bindings.joinToString(",") {
            "${it.type.name}:${it.id}"
        }
        val entity = TreeConfigEntity(
            id = id,
            bindingType = bindingType,
            bindingsSummary = bindingsSummary,
            name = name,
            configData = json,
            enabled = enabled,
            managerId = managerId,
            isTemplate = isTemplate
        )
        repository.save(entity)
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
        return try {
            mapper.readValue(entity.configData, EvaluatorTreeConfig::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun delete(id: String) {
        repository.deleteById(id)
    }

    /** 按 managerId 加载配置（包含全局共享），排除模板 */
    fun loadByManagerId(managerId: String?): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findByManagerId(managerId, includeGlobal = true).map { entity ->
            entity to parseConfig(entity)
        }
    }

    /** 加载所有模板 */
    fun loadTemplates(): List<Pair<TreeConfigEntity, EvaluatorTreeConfig?>> {
        return repository.findTemplates().map { entity ->
            entity to parseConfig(entity)
        }
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class ConditionPayloadMixin

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class RulePayloadMixin

@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "sourceType",
    visible = true
)
@JsonIgnoreProperties(ignoreUnknown = true)
abstract class EvaluatorLeafConfigMixin



