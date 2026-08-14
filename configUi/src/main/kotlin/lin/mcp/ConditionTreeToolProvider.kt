package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.databind.ObjectMapper
import lin.mcp.action.*
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.repository.tree_config.EvaluatorLeafConfigRepository
import lin.rule.condition.ConditionTreeConfig

/**
 * 消费方条件树引用解析（Q-003 内联创建）：提供 treeJson 时自动创建条件树并返回新 id；
 * 否则按 conditionId 引用已有条件树（校验存在）。
 * 一次性树无需先 condition_tree(action=SAVE) 建模板，直接在消费方内联 JSON 一步创建。
 * @param managerId 消费方归属卡组：内联创建时写入树归属（null/空 = 全局共享树）。
 */
fun resolveConditionTreeReference(
    service: ConditionTreeConfigService,
    mapper: ObjectMapper,
    conditionId: String?,
    treeJson: String?,
    defaultName: String,
    label: String,
    managerId: String? = null
): String {
    val hasId = !conditionId.isNullOrBlank()
    val hasJson = !treeJson.isNullOrBlank()
    if (hasId && hasJson) {
        throw McpBadInput("$label：conditionId 与 treeJson 互斥，只能提供其一（复用已有树传 conditionId，一次性树传 treeJson 内联创建）")
    }
    if (hasJson) {
        val config = try {
            mapper.readValue(treeJson, ConditionTreeConfig::class.java)
        } catch (e: Exception) {
            throw McpBadInput(
                "$label 内联条件树 JSON 解析失败（需完整 {id,name,root} 结构，多态节点名用 AndNode/OrNode/NotNode/BranchNode/Leaf）: ${e.message}"
            )
        }
        return service.saveConfig(config.name ?: defaultName, config, managerId = managerId, inlineCreated = true)
    }
    if (!hasId) {
        throw McpBadInput("$label：conditionId 与 treeJson 必须提供其一（一次性树传 treeJson 内联创建，无需先建模板）")
    }
    if (service.findById(conditionId) == null) {
        throw McpBadInput("$label 条件树不存在: $conditionId（一次性树可直接传 treeJson 内联创建，或先 save_condition_tree 建模板）")
    }
    return conditionId
}

/**
 * 条件树（全局逻辑资源）域 MCP 工具提供者（写工具 + 动作同文件）：
 * - [ConditionTreeAction]：resource=condition_tree 的 get/list/delete（原 condition_tree / delete_condition_tree 工具）。
 * - provide()：save_condition_tree 写工具。
 * - 共享的 [resolveConditionTreeReference] 供消费方 provider（save_aura_boost / save_card_group）引用。
 */
class ConditionTreeToolProvider(
    private val service: ConditionTreeConfigService,
    private val auraBoostConfigService: AuraBoostConfigService,
    private val leafConfigRepository: EvaluatorLeafConfigRepository
) : McpToolProvider {

    private val mapper = createConditionTreeConfigMapper()

    override val actions: List<ResourceAction> = listOf(
        ConditionTreeAction(service, auraBoostConfigService, leafConfigRepository)
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<SaveConditionTreeInput>(
            name = "save_condition_tree",
            description = """
                创建或更新一棵条件树（组合逻辑模板），**仅一次性树需要**（或需在多消费方间复用时建模板）：
                消费方（save_aura_boost 的 conditionTreeJson、save_card_group 的 conditionalStageConditionTreeJson）
                已支持直接内联 treeJson 一步创建，无需先调本工具。
                treeJson 传「完整条件树 JSON 的字符串」：{id, name, root}，root 为节点对象。
                叶子 payload 两种：ConditionRef（引用编码条件，conditionId + args）或
                PipelineRef（正交管道：sourceId + transforms + operatorId + operatorArgs + refId）。
                条件树参数（ConditionRef.args / PipelineRef.operatorArgs / transform 参数）直接存树内，
                GET 读取原样返回；评估树 CONDITION_TREE 叶子引用时叶子 args 优先、树内参数兜底
                （最终值 = 消费方覆盖 > 树内默认，Q-002 方案 B）。
                可从 get(resource=condition_tree) 拿现有树复制修改后回传；管道积木用 list_orthogonal_components 查询。
                提供 existingId 更新已有树，否则新建（自动生成 8 位短 id）。
            """.trimIndent()
        ) { input ->
            val config = try {
                mapper.readValue(input.treeJson, ConditionTreeConfig::class.java)
            } catch (e: Exception) {
                return@typedTool mcpError(
                    "treeJson 解析失败（需完整 {id,name,root} 结构，多态节点名用 AndNode/OrNode/NotNode/BranchNode/Leaf）: ${e.message}"
                )
            }
            val id = service.saveConfig(input.name, config, input.existingId, managerId = input.managerId)
            mcpSuccess(mapOf("id" to id, "name" to input.name, "managerId" to input.managerId))
        }
    )

    // ── 动作：condition_tree get/list/delete ──

    private class ConditionTreeAction(
        private val service: ConditionTreeConfigService,
        private val auraBoostConfigService: AuraBoostConfigService,
        private val leafConfigRepository: EvaluatorLeafConfigRepository
    ) : GetAction, ListAction, DeleteAction {

        private val mapper = createConditionTreeConfigMapper()

        override val resource: String = ActionResources.CONDITION_TREE

        override val supportsManagerIdFilter: Boolean = true

        override fun handleList(managerId: String?): McpToolResult {
            return mcpSuccess(
                service.loadAllMeta(managerId).map {
                    mapOf(
                        "id" to it.id,
                        "name" to it.name,
                        "managerId" to it.managerId,
                        "inlineCreated" to it.inlineCreated
                    )
                }
            )
        }

        override fun handleGet(id: String): McpToolResult {
            val entity = service.loadAll().firstOrNull { it.first.id == id }
                ?: return mcpError("条件树不存在: $id")
            val config = entity.second
                ?: return mcpError("条件树配置解析失败: $id")
            return mcpSuccess(
                mapOf(
                    "id" to config.id,
                    "name" to config.name,
                    "managerId" to entity.first.managerId,
                    "inlineCreated" to entity.first.inlineCreated,
                    "configData" to mapper.writeValueAsString(config)
                )
            )
        }

        override val getFieldHint: String = "条件树 id（8 位短 id，由 list(resource=condition_tree) 返回）"

        override fun handleDelete(id: String): McpToolResult {
            val meta = service.loadAllMeta().firstOrNull { it.id == id }
                ?: return mcpError("条件树不存在: $id")

            val referencers = findReferencers(id)
            if (referencers.isNotEmpty()) {
                val depInfo = referencers.joinToString("\n") { "  - $it" }
                return mcpError(
                    "无法删除条件树 [${meta.name}] (id=$id)，以下引用方依赖此树:\n$depInfo\n" +
                            "请先解除这些引用（修改 AuraBoost / 评估树叶子）后重试。"
                )
            }

            // 删除前捕获完整配置；restoreConfigData 用 configUi mapper 重新序列化，保证与 save_condition_tree
            // 解析格式一致（误删恢复往返可用）。
            val loaded = service.loadAll().firstOrNull { it.first.id == id }
            val restoreData = loaded?.second?.let { mapper.writeValueAsString(it) }
                ?: loaded?.first?.configData
                ?: ""
            service.delete(id)
            return mcpSuccess(
                mapOf(
                    "deleted" to meta.id,
                    "name" to meta.name,
                    "managerId" to (meta.managerId ?: ""),
                    "inlineCreated" to meta.inlineCreated,
                    "restoreConfigData" to restoreData,
                    "restoreHint" to "误删恢复：将 restoreConfigData 原样作为 save_condition_tree 的 treeJson 参数（name=原name，managerId=原managerId）即可重建"
                )
            )
        }

        override val deleteFieldHint: String = "条件树 id（8 位短 id，由 list(resource=condition_tree) 返回）"

        override val deleteSemantics: String =
            "删除前检查引用方（AuraBoost / 评估树叶子），有引用则拒绝；删除返回完整 restoreConfigData 可经 save_condition_tree 恢复"

        /**
         * 扫描条件树的引用方（删除前安全检查）。
         * @return 引用方描述列表（空 = 无引用，可安全删除）
         */
        private fun findReferencers(conditionTreeId: String): List<String> {
            val refs = mutableListOf<String>()

            // 1. AuraBoost 引用（condition_id / target_condition_id）
            auraBoostConfigService.loadAll().forEach { ab ->
                if (ab.conditionId == conditionTreeId) {
                    refs += "aura_boost ${ab.id}（conditionId，name=${ab.name ?: "?"}）"
                }
                if (ab.targetConditionId == conditionTreeId) {
                    refs += "aura_boost ${ab.id}（targetConditionId，name=${ab.name ?: "?"}）"
                }
            }

            // 2. 评估树叶子引用（CONDITION_TREE 叶子 sourceId）
            leafConfigRepository.findAllRaw().forEach { (configId, leafJson) ->
                if (leafJson.contains("\"CONDITION_TREE\"") && leafJson.contains("\"sourceId\":\"$conditionTreeId\"")) {
                    refs += "评估树叶子（config_id=$configId）"
                }
            }

            return refs.distinct()
        }
    }
}

private data class SaveConditionTreeInput(
    @field:JsonPropertyDescription("条件树名称（用途命名建议：sort_/boost_ 前缀区分排序/push 用途）")
    val name: String,
    @field:JsonPropertyDescription("完整条件树 JSON（字符串类型）：{id, name, root}。必须传「序列化后的 JSON 文本」作为整体字符串，不要传嵌套对象。")
    val treeJson: String,
    @field:JsonPropertyDescription("可选：归属卡组 managerId（来自 card_group(action=LIST)）。null/不传 = 全局共享树。")
    val managerId: String? = null,
    @field:JsonPropertyDescription("可选：更新已有条件树时传其 id；不传则新建。")
    val existingId: String? = null
)
