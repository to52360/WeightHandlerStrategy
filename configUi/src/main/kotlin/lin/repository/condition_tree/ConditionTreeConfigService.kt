package lin.repository.condition_tree

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.repository.delete_snapshot.*
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionTreeConfig
import lin.ui.condition_tree.validation.ConditionTreeValidator
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
 * 条件树配置服务（语义 C，D-007 + 2026-08-09 Q-002 方案 B 演进）。
 *
 * 条件树 config_data 存完整参数（ConditionRef.args + PipelineRef.operatorArgs + transform call.args 全在树内）。
 * - 排序/AuraBoost（GuardCompiler.compileTree）：消费方无参数通道，直接用树内参数裸编译。
 * - 评估树（GuardCompiler.buildConditionTreeLogic）：消费方 args（叶子）优先 + 树内参数兜底，
 *   最终值 = 消费方覆盖 > 树内默认（Q-002 方案 B；注意改树内参数会静默影响所有未显式覆盖的消费方）。
 */
class ConditionTreeConfigService(
    private val repository: ConditionTreeConfigRepository,
    private val mapper: ObjectMapper,
    private val conditionTreeValidator: ConditionTreeValidator
) {

    // ─────────────────── 删除 + 快照 / 恢复（T-TG-021：业务归域 + 机制由快照域编排）───────────────────

    /**
     * 导出本资源的删除操作值；不存在 / 配置无法解析时抛 [SnapshotRefused]（不落快照、不删除）。
     *
     * ⚠️ **引用校验不在此处**（AuraBoost / 评估树叶子是跨域查询）—— 由 MCP 层 `ConditionTreeToolProvider` 前置。
     */
    fun deleteOps(): SnapshotOps = SnapshotOps(
        collect = { entityId ->
            val meta = loadAllMeta().firstOrNull { it.id == entityId }
                ?: throw SnapshotRefused("条件树不存在: $entityId")
            val loaded = loadAll().firstOrNull { it.first.id == entityId }
                ?: throw SnapshotRefused("条件树不存在: $entityId")
            val config = loaded.second
                ?: throw SnapshotRefused("条件树配置解析失败，无法采集快照，拒绝删除: $entityId")
            SnapshotDraft(
                entityName = meta.name,
                payload = SnapshotPayloads.conditionTree(loaded.first, config),
                echo = mapOf("deleted" to meta.id, "name" to meta.name)
            )
        },
        remove = { entityId -> delete(entityId) }
    )

    /** 按快照写回（原 id 保留）；原 id 已被占用则拒绝。 */
    fun restoreFromSnapshot(id: String, payload: String): RestoreResult {
        loadAll().firstOrNull { it.first.id == id }?.let { occupied ->
            return RestoreResult(
                "恢复失败：原 id=$id 已被现有数据占用（现有名称: ${occupied.first.name}）。请先删除/改名现有数据再恢复",
                isError = true
            )
        }
        val p = SnapshotPayloads.conditionTreeMapper.readValue(payload, ConditionTreeSnapshot::class.java)
        saveConfig(
            name = p.config.name ?: id,
            config = p.config,
            existingId = id,
            managerId = p.managerId?.takeIf { it.isNotBlank() },
            inlineCreated = p.inlineCreated
        )
        return RestoreResult("已恢复 condition_tree $id（原 id 保留）", isError = false)
    }
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
        // 分界校验：内联创建（inlineCreated，消费方自动建的一次性树，实际使用，含全局光环）→ 参数必须完整；
        // 工作台/MCP 建的模板（骨架，供评估树叶子覆盖参数参考）→ 参数不影响使用，不校验。
        if (inlineCreated) {
            val errors = conditionTreeValidator.validateArgs(config)
            if (errors.isNotEmpty()) {
                throw IllegalArgumentException("条件树 [${config.name}] 参数不完整：\n${errors.joinToString("\n")}")
            }
        }
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
