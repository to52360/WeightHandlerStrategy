package lin.mcp.combo_plan

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.ComboRelation
import lin.mcp.*
import lin.mcp.action.ResourceAction
import lin.repository.card_group.CardGroupService
import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.utils.nextShortId

/**
 * Combo 域 MCP 工具提供者（写工具 + 动作分文件，一个资源域一个包）：
 * - [ComboPlanAction]：resource=combo_plan 的 get/list/delete（独立文件 ComboPlanAction.kt，
 *   Provider 内部持有，不进 Koin；已有的依赖构造传入，缺失依赖内部 inject）。
 * - provide()：save_combo_plan 写工具。
 * - DTO 定义见 ComboPlanDtos.kt（供 save / delete 快照 / action 共用）。
 */
class ComboPlanToolProvider(
    private val repository: ComboPlanDefinitionRepository,
    private val cardGroupService: CardGroupService
) : McpToolProvider {

    private val comboPlanAction = ComboPlanAction(repository, cardGroupService)

    override val actions: List<ResourceAction> = listOf(comboPlanAction)

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<SaveComboPlanInput>(
            name = "save_combo_plan",
            description = """
                创建或修改 Combo 战术编排方案。
                会校验 managerId 以及 coreGroupIds/depGroupIds 是否属于该卡组的有效绑定条目；
                id 为空时会自动分配 short ID；如果包含已有 id，则会覆盖保存该 Combo 方案。
            """.trimIndent()
        ) { input ->
            val allManagers = cardGroupService.loadAll(onlyEnabled = false)
            val manager = allManagers.find { m -> m.cardGroupManagerId == input.managerId }
                ?: return@typedTool mcpError(
                    "卡组/管理器不存在: ${input.managerId}。当前存在的卡组列表: ${
                        allManagers.map {
                            mapOf(
                                "id" to it.cardGroupManagerId,
                                "name" to it.name
                            )
                        }
                    }"
                )

            if (input.coreGroupIds.isEmpty()) {
                return@typedTool mcpError("coreGroupIds 核心分组 ID 列表不能为空，必须包含至少一个有效分组 ID")
            }

            if (input.depGroupIds.isEmpty()) {
                return@typedTool mcpError("depGroupIds 依赖分组 ID 列表不能为空！Combo 方案必须包含核心组与依赖组才能构成协同加分或时序关联。")
            }

            val validBindingIds = manager.bindings.map { b -> b.id }.toSet()
            val invalidCoreIds = input.coreGroupIds.filterNot { it in validBindingIds }
            val invalidDepIds = input.depGroupIds.filterNot { it in validBindingIds }

            if (invalidCoreIds.isNotEmpty() || invalidDepIds.isNotEmpty()) {
                return@typedTool mcpError(
                    "提供的分组 ID 不属于卡组 [${manager.name}] (${input.managerId})。" +
                            "无效核心组: $invalidCoreIds, 无效依赖组: $invalidDepIds。" +
                            "该卡组下的有效分组绑定列表: ${
                                manager.bindings.map {
                                    mapOf(
                                        "id" to it.id,
                                        "name" to it.name
                                    )
                                }
                            }"
                )
            }

            val relationEnum = try {
                ComboRelation.valueOf(input.relation.uppercase())
            } catch (_: Exception) {
                return@typedTool mcpError(
                    "未知 relation: ${input.relation}。合法选项: ${
                        ComboRelation.entries.map { it.name }
                    }"
                )
            }

            val finalId = input.id?.trim()?.takeIf { it.isNotEmpty() } ?: nextShortId()

            val entity = ComboPlanDefinitionEntity(
                managerId = input.managerId,
                id = finalId,
                coreGroupIds = input.coreGroupIds.joinToString(","),
                depGroupIds = input.depGroupIds.joinToString(","),
                score = input.score,
                coreMutex = input.coreMutex,
                relation = relationEnum.name,
                mustAdjacent = input.mustAdjacent
            )

            repository.save(entity)

            mcpSuccess(
                mapOf(
                    "saved" to true,
                    "id" to finalId,
                    "managerId" to input.managerId,
                    "managerName" to manager.name
                )
            )
        }
    )
}

private data class SaveComboPlanInput(
    @field:JsonPropertyDescription("卡组/管理器 ID（来自 list(resource=card_group) 返回的 id，或 save_card_group 响应的 managerId），必填")
    val managerId: String,

    @field:JsonPropertyDescription("Combo 方案 ID。若是新建，留空时系统将自动分配短 ID；若是编辑现有方案，传入对应 id")
    val id: String? = null,

    @field:JsonPropertyDescription("核心卡牌分组 ID 列表（绑定条目 id，来自 get(resource=card_group) 的 bindings.id），至少包含一个")
    val coreGroupIds: List<String>,

    @field:JsonPropertyDescription("依赖卡牌分组 ID 列表（绑定条目 id），不能为空。Combo 方案必须包含核心组与依赖组才能构成协同")
    val depGroupIds: List<String> = emptyList(),

    @field:JsonPropertyDescription("Combo 加分（如 3.0，负值如 -2.0 表达软惩罚）。【纯排序配方：固定 0】仅表达先后顺序不加分时必填 0，此时配合 relation=CORE_BEFORE_DEP/DEP_BEFORE_CORE 即为纯排序（如「先铺场再清场」的出牌次序），不会扭曲权重竞争")
    val score: Double = 0.0,

    @field:JsonPropertyDescription("核心组内互斥：同 Combo 下多个核心组候选不能同时打出时置为 true。⚠️陷阱一：默认 true——纯排序配方必须显式置 false，否则多核心组会被互斥剪枝、只剩一组参与排序。⚠️陷阱二：该值同时被起手换牌消费（多个核心组不能同时保留），置 false 会连带取消起手侧的保留互斥——若该 Combo 的核心牌在起手阶段也不希望同时留手，需另行确认")
    val coreMutex: Boolean = true,

    @field:JsonPropertyDescription("顺序关系：SCORE_ONLY（纯加分无顺序）/ CORE_BEFORE_DEP（核心组先出）/ DEP_BEFORE_CORE（依赖组先出），默认 SCORE_ONLY。【纯排序配方】score=0 + coreMutex=false + relation=二者之一：表达「A 组的牌恒先于 B 组的牌」，与 orderWeight 的区别是可跨阶段拉动（拓扑约束跑在阶段排序之上）")
    val relation: String = "SCORE_ONLY",

    @field:JsonPropertyDescription("【当前版本无效，请勿配置】必须连续/相邻打出。引擎侧 UsePlanOrderer 尚未消费该语义（强相邻需先设计组块/窗口模型），配置后不产生任何效果，默认 false")
    val mustAdjacent: Boolean = false
)
