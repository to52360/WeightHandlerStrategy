package lin.ui.service

import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.condition.ConditionPayload
import lin.rule.score.ScoreEffect
import lin.rule.tree.*
import lin.ui.tree_config.db.EvaluatorLeafConfigRepository
import lin.ui.tree_config.db.EvaluatorTreeTemplateEntity
import lin.ui.tree_config.db.EvaluatorTreeTemplateRepository
import java.util.*

class EvaluatorTreeTemplateService(
    private val templateRepository: EvaluatorTreeTemplateRepository,
    private val leafConfigRepository: EvaluatorLeafConfigRepository,
    private val mapper: ObjectMapper
) {
    private data class RootHolder(val root: LogicNode<EvaluatorPayload>)

    fun saveTemplate(
        name: String,
        config: EvaluatorTreeConfig,
        description: String? = null,
        groupId: String? = null,
        existingId: String? = null,
        nodeNames: Map<String, String>? = null
    ): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        val rootJson = mapper.writeValueAsString(RootHolder(config.root))
        val nodeNamesJson = if (nodeNames.isNullOrEmpty()) null else mapper.writeValueAsString(nodeNames)

        val entity = EvaluatorTreeTemplateEntity(
            id = id,
            name = name,
            description = description,
            groupId = groupId,
            configData = rootJson,
            nodeNames = nodeNamesJson
        )
        templateRepository.save(entity)

        // T-015: 模板只存结构不含参数 (D-007)
        val strippedLeafConfigs = config.leafConfigs.mapValues { (_, leaf) ->
            when (leaf) {
                is OrthogonalConditionLeafConfig -> leaf.copy(
                    nodeId = "",
                    args = emptyMap(),
                    guardCondition = leaf.guardCondition.copy(
                        operatorArgs = emptyMap(),
                        transforms = leaf.guardCondition.transforms.map { it.copy(args = emptyMap()) },
                        refId = ""
                    )
                )

                is OrthogonalRuleLeafConfig -> leaf.copy(
                    nodeId = "",
                    args = emptyMap(),
                    guardCondition = leaf.guardCondition?.let { stripConditionArgs(it) },
                    scoreEffect = stripScoreArgs(leaf.scoreEffect)
                )

                is RuleLeafConfig -> leaf.copy(
                    nodeId = "",
                    args = emptyMap(),
                    guardCondition = leaf.guardCondition?.let { stripConditionArgs(it) },
                    scoreEffect = stripScoreArgs(leaf.scoreEffect)
                )

                is ConditionLeafConfig -> leaf.copy(nodeId = "", args = emptyMap())
                is ConditionTreeLeafConfig -> leaf.copy(nodeId = "", args = emptyMap())
            }
        }
        leafConfigRepository.saveAll(id, strippedLeafConfigs, mapper)
        return id
    }

    fun loadAllTemplates(): List<Pair<EvaluatorTreeTemplateEntity, EvaluatorTreeConfig?>> {
        return templateRepository.findAll().map { entity ->
            entity to parseTemplateConfig(entity)
        }
    }

    fun findById(id: String): Pair<EvaluatorTreeTemplateEntity, EvaluatorTreeConfig?>? {
        val entity = templateRepository.findById(id) ?: return null
        return entity to parseTemplateConfig(entity)
    }

    private fun parseTemplateConfig(entity: EvaluatorTreeTemplateEntity): EvaluatorTreeConfig? {
        val root = try {
            mapper.readValue(entity.configData, RootHolder::class.java).root
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
        val leafConfigs = leafConfigRepository.findByConfigId(entity.id, mapper)
        return EvaluatorTreeConfig(
            bindingType = EvaluatorTreeBindingType.GROUP,
            bindingIds = emptyList(),
            root = root,
            leafConfigs = leafConfigs
        )
    }

    fun deleteTemplate(id: String) {
        leafConfigRepository.deleteByConfigId(id)
        templateRepository.deleteById(id)
    }

    // T-015: 模板参数剥离 (D-007)

    private fun stripConditionArgs(payload: ConditionPayload): ConditionPayload = when (payload) {
        is ConditionPayload.PipelineRef -> payload.copy(
            operatorArgs = emptyMap(),
            transforms = payload.transforms.map { it.copy(args = emptyMap()) },
            refId = ""
        )

        is ConditionPayload.ConditionRef -> payload.copy(args = emptyMap())
    }

    private fun stripScoreArgs(scoreEffect: ScoreEffect): ScoreEffect = when (scoreEffect) {
        is ScoreEffect.SourceScore -> scoreEffect.copy(
            operatorArgs = emptyMap(),
            transforms = scoreEffect.transforms.map { it.copy(args = emptyMap()) }
        )

        else -> scoreEffect
    }
}
