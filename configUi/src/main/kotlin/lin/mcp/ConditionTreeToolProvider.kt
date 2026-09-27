package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.action.*
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.repository.delete_snapshot.SnapshotOps
import lin.repository.delete_snapshot.SnapshotRefused
import lin.repository.tree_config.EvaluatorLeafConfigRepository
import lin.rule.condition.ConditionTreeConfig

/**
 * 条件树（全局逻辑资源）域 MCP 工具提供者（写工具 + 动作同文件）：
 * - resource=condition_tree 的 get/list/delete（原 condition_tree / delete_condition_tree 工具）。
 * - provide()：save_condition_tree 写工具。
 * - 消费方引用解析 [resolveConditionTreeReference] 已下沉至 repository 层（T-011），
 *   由 save_aura_boost / save_card_group 等服务层编排复用。
 */
class ConditionTreeToolProvider(
    private val service: ConditionTreeConfigService,
    /** 引用方扫描依赖（aura_boost + 评估树叶子）是跨域查询，本 Provider 自己要用 ⇒ 构造注入。 */
    private val auraBoostConfigService: AuraBoostConfigService,
    private val leafConfigRepository: EvaluatorLeafConfigRepository
) : McpToolProvider {

    private val mapper = createConditionTreeConfigMapper()

    override val actions: List<ResourceActions> = listOf(
        ResourceActions(
            resource = ActionResources.CONDITION_TREE,
            capabilities = listOf(
                GetCapability(
                    fieldHint = "条件树 id（8 位短 id，由 list(resource=condition_tree) 返回）"
                ) { id -> conditionTreeDetail(id) },
                ListCapability(supportsManagerIdFilter = true) { managerId -> conditionTreeSummaries(managerId) },
                DeleteCapability(
                    fieldHint = "条件树 id（8 位短 id，由 list(resource=condition_tree) 返回）",
                    semantics = "删除前检查引用方（AuraBoost / 评估树叶子），有引用则拒绝；删除前落快照（delete_snapshot）并回 snapshotId，可经 restore_snapshot 一键恢复（原 id 保留）",
                    ops = guardedConditionTreeOps()
                ),
                RestoreCapability { entityId, payload ->
                    service.restoreFromSnapshot(entityId, payload)
                }
            )
        )
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<SaveConditionTreeInput>(
            name = "save_condition_tree",
            description = """
                创建或更新一棵条件树（组合逻辑模板），**仅一次性树需要**（或需在多消费方间复用时建模板）：
                消费方（save_aura_boost 的 conditionTreeJson、save_card_group 的 conditionalStageConditionTreeJson）
                已支持直接内联 treeJson 一步创建，无需先调本工具。
                treeJson 传「完整条件树 JSON 的字符串」：{id, name, root}。叶子节点结构与示例模板见
                list_orthogonal_components 的 orthogonal_leaf_json_templates（含各参数位点）。
                树内参数作为默认值，被消费方引用时叶子 args 优先（最终值 = 消费方覆盖 > 树内默认）。
                可从 get(resource=condition_tree) 拿现有树复制修改后回传。
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

    // ── 能力实现（condition_tree 的 get / list）：具名私有函数，行为可点名 ──

    /** list：条件树摘要（可按 managerId 过滤）。 */
    private fun conditionTreeSummaries(managerId: String?): McpToolResult {
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

    /** get：条件树详情（含原样 config JSON）。 */
    private fun conditionTreeDetail(id: String): McpToolResult {
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

    /**
     * 删除前校验 + 采集：引用方扫描是跨域查询 ⇒ 留在 MCP 层包装；存在性/解析由域侧 collect 单点负责。
     */
    private fun guardedConditionTreeOps(): SnapshotOps {
        val base = service.deleteOps()
        return base.copy(collect = { id ->
            val referencers = findReferencers(id)
            if (referencers.isNotEmpty()) {
                val name = service.loadAllMeta().firstOrNull { it.id == id }?.name
                    ?: throw SnapshotRefused("条件树不存在: $id")
                val depInfo = referencers.joinToString("\n") { "  - $it" }
                throw SnapshotRefused(
                    "无法删除条件树 [$name] (id=$id)，以下引用方依赖此树:\n$depInfo\n" +
                            "请先解除这些引用（修改 AuraBoost / 评估树叶子）后重试。"
                )
            }
            base.collect(id)
        })
    }

    /**
     * 扫描条件树的引用方（删除前安全检查）。
     * @return 引用方描述列表（空 = 无引用，可安全删除）
     */
    private fun findReferencers(conditionTreeId: String): List<String> {
        val refs = mutableListOf<String>()
        auraBoostConfigService.loadAll().forEach { ab ->
            if (ab.conditionId == conditionTreeId) {
                refs += "aura_boost ${ab.id}（conditionId，name=${ab.name ?: "?"}）"
            }
            if (ab.targetConditionId == conditionTreeId) {
                refs += "aura_boost ${ab.id}（targetConditionId，name=${ab.name ?: "?"}）"
            }
        }
        leafConfigRepository.findAllRaw().forEach { (configId, leafJson) ->
            if (leafJson.contains("\"CONDITION_TREE\"") && leafJson.contains("\"sourceId\":\"$conditionTreeId\"")) {
                refs += "评估树叶子（config_id=$configId）"
            }
        }
        return refs.distinct()
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
