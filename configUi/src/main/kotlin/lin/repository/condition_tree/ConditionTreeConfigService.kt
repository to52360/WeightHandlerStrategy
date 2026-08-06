package lin.repository.condition_tree

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.jsontype.NamedType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.rule.condition.ConditionNode
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionTreeConfig
import lin.rule.condition.collectConditionRefs
import lin.rule.parse.extractPrefixedArgs
import lin.rule.tree.LogicNode
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

class ConditionTreeConfigService(
    private val repository: ConditionTreeConfigRepository,
    private val mapper: ObjectMapper,
    private val argsRepository: ConditionTreeArgsRepository
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
        // D-005 拆参：编码条件(ConditionRef)参数入旁挂表，条件树 JSON 保持纯结构（消除默认值/实体双语义）。
        argsRepository.save(id, collectCodedArgs(config.root))
        val stripped = config.copy(root = stripCodedArgs(config.root))
        val json = mapper.writeValueAsString(stripped.copy(id = id, name = name))
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
        argsRepository.deleteByTreeId(id)
    }

    /** 收集条件树所有编码条件(ConditionRef)参数为 prefixed flat map（refId.propertyName → value）。 */
    private fun collectCodedArgs(root: ConditionNode): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        root.collectConditionRefs()
            .filterIsInstance<ConditionPayload.ConditionRef>()
            .forEach { ref ->
                ref.args.forEach { (key, value) -> result["${ref.refId}.$key"] = value }
            }
        return result
    }

    /** 递归清除条件树内 ConditionRef 的 args（PipelineRef 结构参数保留），使树 JSON 为纯结构模板。 */
    private fun stripCodedArgs(node: ConditionNode): ConditionNode = when (node) {
        is LogicNode.Leaf -> node.copy(payload = stripPayload(node.payload))
        is LogicNode.And -> node.copy(children = node.children.map { stripCodedArgs(it) })
        is LogicNode.Or -> node.copy(children = node.children.map { stripCodedArgs(it) })
        is LogicNode.Not -> node.copy(child = stripCodedArgs(node.child))
        is LogicNode.Branch -> node.copy(
            payload = stripPayload(node.payload),
            onTrue = stripCodedArgs(node.onTrue),
            onFalse = stripCodedArgs(node.onFalse)
        )
    }

    private fun stripPayload(payload: ConditionPayload): ConditionPayload = when (payload) {
        is ConditionPayload.ConditionRef -> payload.copy(args = emptyMap())
        is ConditionPayload.PipelineRef -> payload
    }

    /**
     * 读时合并旁挂表参数回 ConditionRef（工作台/编辑器回显用）。
     * 条件树 JSON 是纯结构，编译路径不依赖此合并（评估树走 leafConfig.args、排序/AuraBoost 走旁表）。
     */
    private fun mergeCodedArgs(node: ConditionNode, args: Map<String, Any>): ConditionNode = when (node) {
        is LogicNode.Leaf -> node.copy(payload = mergePayload(node.payload, args))
        is LogicNode.And -> node.copy(children = node.children.map { mergeCodedArgs(it, args) })
        is LogicNode.Or -> node.copy(children = node.children.map { mergeCodedArgs(it, args) })
        is LogicNode.Not -> node.copy(child = mergeCodedArgs(node.child, args))
        is LogicNode.Branch -> node.copy(
            payload = mergePayload(node.payload, args),
            onTrue = mergeCodedArgs(node.onTrue, args),
            onFalse = mergeCodedArgs(node.onFalse, args)
        )
    }

    private fun mergePayload(payload: ConditionPayload, args: Map<String, Any>): ConditionPayload = when (payload) {
        is ConditionPayload.ConditionRef -> {
            val refArgs = args.extractPrefixedArgs(payload.refId)
            if (refArgs.isNotEmpty()) payload.copy(args = refArgs) else payload
        }

        is ConditionPayload.PipelineRef -> payload
    }

    /**
     * 返回条件树元数据（含 inlineCreated 标记）。
     * managerId 非空时按"当前卡组私有 + 全局共享"过滤；为空则全量返回。
     */
    fun loadAllMeta(managerId: String? = null): List<ConditionTreeMeta> {
        return repository.findMetaByManagerId(managerId)
    }

    private fun readConfig(entity: ConditionTreeConfigEntity): ConditionTreeConfig? {
        val config = try {
            mapper.readValue(entity.configData, ConditionTreeConfig::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } ?: return null
        // D-005 回显：把旁挂表参数合并回 ConditionRef（工作台/编辑器显示用；编译路径不依赖此合并）
        val args = argsRepository.findById(entity.id)
        if (args.isNullOrEmpty()) return config
        return config.copy(root = mergeCodedArgs(config.root, args))
    }
}
