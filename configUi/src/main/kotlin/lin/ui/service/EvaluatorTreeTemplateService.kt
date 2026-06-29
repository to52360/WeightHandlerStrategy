package lin.ui.service

import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.EvaluatorTreeBindingType
import lin.rule.tree.EvaluatorTreeConfig
import lin.rule.tree.LogicNode
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
        existingId: String? = null
    ): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        val rootJson = mapper.writeValueAsString(RootHolder(config.root))

        val entity = EvaluatorTreeTemplateEntity(
            id = id,
            name = name,
            description = description,
            groupId = groupId,
            configData = rootJson
        )
        templateRepository.save(entity)
        leafConfigRepository.saveAll(id, config.leafConfigs, mapper)
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
}
