package lin.repository.condition_tree

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionTreeConfig
import lin.utils.json.registerLogicNodeMixin
import java.util.*

fun createConditionTreeConfigMapper(): ObjectMapper {
    return jacksonObjectMapper()
        .registerLogicNodeMixin()
        .addMixIn(ConditionPayload::class.java, ConditionPayloadMixin::class.java)
        .apply {
            registerSubtypes(
                NamedType(ConditionPayload.ConditionRef::class.java, "ConditionRef"),
                NamedType(ConditionPayload.PipelineRef::class.java, "PipelineRef")
            )
        }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.WRAPPER_OBJECT)
abstract class ConditionPayloadMixin

/**
 * 条件树配置服务（语义 B，D-007）。
 *
 * 条件树 config_data 存完整参数（ConditionRef.args + PipelineRef.operatorArgs + transform call.args 全在树内）。
 * - 排序/AuraBoost（GuardCompiler.compileTree）：消费方无参数通道，直接用树内参数裸编译。
 * - 评估树（GuardCompiler.buildConditionTreeLogic）：叶子 args 是唯一参数来源，树内参数仅作表单预填参考
 *   （改树内参数不影响已配置的评估树叶子）。
 */
class ConditionTreeConfigService(
    private val repository: ConditionTreeConfigRepository,
    private val mapper: ObjectMapper
) {
    /**
     * @param managerId 归属卡组：null = 全局共享树；非 null = 卡组私有树（空字符串自动归一化为 null，
     *                  存储层只存在 null 一种"全局"表示，与 tree_config 的 null 语义一致）。
     * @param inlineCreated true 表示由消费方（save_aura_boost/save_card_group）内联自动创建的一次性树，
     *                      不参与评估树背景知识（list_capability_background）展示。
     */
    fun saveConfig(
        name: String,
        config: ConditionTreeConfig,
        existingId: String? = null,
        managerId: String? = null,
        inlineCreated: Boolean = false
    ): String {
        val id = existingId ?: UUID.randomUUID().toString().substring(0, 8)
        val json = mapper.writeValueAsString(config.copy(id = id, name = name))
        repository.save(
            ConditionTreeConfigEntity(
                id = id,
                name = name,
                configData = json,
                managerId = managerId?.takeIf { it.isNotBlank() },
                inlineCreated = inlineCreated
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

    /**
     * 返回条件树元数据（含 inlineCreated 标记）。
     * managerId 非空时按"当前卡组私有 + 全局共享"过滤；为空则全量返回。
     */
    fun loadAllMeta(managerId: String? = null): List<ConditionTreeMeta> {
        return repository.findMetaByManagerId(managerId)
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
