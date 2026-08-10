package lin.mcp.card_group

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.bean.usePlan.ConditionalStageOverride
import lin.bean.usePlan.GroupUseOverride
import lin.bean.usePlan.UseStage
import lin.dao.CardGroupJsonParser
import lin.mcp.*
import lin.repository.card_group.CardGroupService
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.repository.condition_tree.createConditionTreeConfigMapper
import lin.rule.tree.CardGroupBehavior
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.findOverride
import lin.utils.nextShortId

/**
 * 卡组策略配置域 MCP 工具提供者（save_card_group + save_group_override）。
 * 分步语义（2026-08-10 用户拍板）：**分组归分组、卡组策略归策略**——
 * - `save_card_group` 只做分组定义（name/cardIds/description），更新时保留已有策略（behaviors），不碰出牌阶段；
 * - `group_override` 单独配置/清除分组的出牌阶段策略（stageOverride / conditionalStage，clearOverride 清除）。
 * 对照 architecture-context/config-tooling/ 文档。
 */
class SaveCardGroupToolProvider(
    private val groupService: CardGroupService,
    private val conditionTreeService: ConditionTreeConfigService
) : McpToolProvider {

    private val conditionTreeMapper = createConditionTreeConfigMapper()

    /** override 展示视图（clearOverride 返回 previousOverride 用，与 SET 返回结构一致）。 */
    private fun overrideView(override: GroupUseOverride?): Map<String, Any?> = mapOf(
        "stageOverride" to override?.stageOverride?.name,
        "conditionalStage" to override?.conditionalStage?.let { cs ->
            mapOf(
                "conditionId" to cs.conditionId,
                "stage" to cs.stage.name,
                "elseStage" to cs.elseStage?.name
            )
        }
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<SaveCardGroupInput>(
            name = "save_card_group",
            description = """创建或更新卡牌分组方案（**仅分组定义**：name/cardIds/description）。
- 正常模式（创建/更新）：提供 sourceFile + bindings；existingId 非空时更新该方案，为空则自动按 managerName/sourceFile 覆盖更新已有方案
- 克隆模式：提供 cloneFrom，以该方案为蓝本创建副本，含所有 binding 与 behavior，managerName 缺省自动加「副本」后缀
- **分步提交**：本工具只提交分组归属（cardIds），不设置出牌策略；分组更新时保留已配置的策略（stageOverride/conditionalStage）。
  出牌阶段策略用 `save_group_override` 单独配置。

【恢复/修改上下文最佳实践】
在任何重启任务或续接对话的场景中，强烈建议先调用 `card_group(action="LIST")` 获取已有的 managerId，并调用 `card_group(action="GET", managerId="...")` 探查现有分组及其 bindingIds；如需更新则传入 existingId。若未传 existingId 但 managerName/sourceFile 相同，系统也会自动覆盖同名方案，不会产生多余重复项。

方案级可配进度跟踪字段（2026-08-10）：
- managerDescription：卡组总体描述/规划（战术主题、配置目标），供续接时快速恢复上下文
- managerStatus：配置进度状态（PLANNED=已规划未开始 / IN_PROGRESS=配置中 / CONFIGURED=已完成可运行），缺省不覆盖原值"""
        ) { input ->
            // ── clone mode ──
            if (input.cloneFrom != null) {
                val sourceManager = groupService.loadAllManagers().firstOrNull { it.id == input.cloneFrom }
                    ?: return@typedTool mcpError("要克隆的方案不存在: ${input.cloneFrom}")
                val sourceBindings = groupService.loadBindings(input.cloneFrom)
                val effectiveSourceFile = input.sourceFile?.takeIf { it.isNotBlank() } ?: sourceManager.sourceFile
                val managerName = input.managerName?.takeIf { it.isNotBlank() }
                    ?: "${sourceManager.name} 副本"

                val clonedBindings = sourceBindings.map { b ->
                    CardGroupBinding(
                        id = nextShortId(),
                        managerId = "",
                        name = b.name,
                        cardIds = b.cardIds,
                        description = b.description,
                        behaviors = b.behaviors
                    )
                }

                val managerId = groupService.saveManager(
                    name = managerName,
                    sourceFile = effectiveSourceFile,
                    enabled = true,
                    bindings = clonedBindings,
                    existingId = null,
                    // 克隆副本缺省继承源方案的 meta（与更新模式"缺省不覆盖"语义一致）
                    managerDescription = input.managerDescription?.takeIf { it.isNotBlank() }
                        ?: sourceManager.description,
                    managerStatus = input.managerStatus?.takeIf { it.isNotBlank() } ?: sourceManager.status
                )
                return@typedTool mcpSuccess(
                    mapOf(
                        "managerId" to managerId,
                        "managerName" to managerName,
                        "bindingIds" to clonedBindings.map { it.id },
                        "clonedFrom" to input.cloneFrom
                    )
                )
            }

            // ── original create/update logic（纯分组定义）──
            val sourceFile = input.sourceFile ?: return@typedTool mcpError("sourceFile 不能为空")
            if (input.bindings.isEmpty()) return@typedTool mcpError("bindings 不能为空，至少需要一个分组绑定条目")
            val cardPool = CardGroupJsonParser.loadByFileName(sourceFile)
                ?: return@typedTool mcpError("sourceFile not found: $sourceFile")
            val validCardIds = cardPool.cards.map { it.cardId }.toSet()
            input.bindings.forEachIndexed { i, bi ->
                val invalidIds = bi.cardIds.filter { it !in validCardIds }
                if (invalidIds.isNotEmpty()) {
                    return@typedTool mcpError("bindings[$i] contains cardIds not in sourceFile '$sourceFile': $invalidIds")
                }
            }
            val managerName = input.managerName?.takeIf { it.isNotBlank() } ?: sourceFile
            val existingId = input.existingId?.takeIf { it.isNotBlank() }
            // Q-009：更新模式（显式 existingId 或同名/同源方案）下按 name 复用旧 binding id，
            // 避免整体 replaceBindings 重建 id 导致 combo_plan / 评估树 bindingId 引用断裂。
            val targetManagerId = existingId
                ?: groupService.loadAllManagers()
                    .firstOrNull { it.name == managerName || it.sourceFile == sourceFile }
                    ?.id
            val existingBindingsByName: Map<String, CardGroupBinding> = if (targetManagerId != null) {
                groupService.loadBindings(targetManagerId).associateBy { it.name }
            } else {
                emptyMap()
            }
            // 分步语义：只更新分组定义（cardIds/description），保留已有策略（behaviors）——策略由 save_group_override 单独维护。
            val bindings = input.bindings.map { bi ->
                val existing = existingBindingsByName[bi.name]
                CardGroupBinding(
                    id = existing?.id ?: nextShortId(),
                    managerId = "",
                    name = bi.name,
                    cardIds = bi.cardIds,
                    description = bi.description,
                    behaviors = existing?.behaviors ?: emptyList()
                )
            }
            val managerId = groupService.saveManager(
                name = managerName,
                sourceFile = sourceFile,
                enabled = true,
                bindings = bindings,
                existingId = existingId,
                managerDescription = input.managerDescription,
                managerStatus = input.managerStatus
            )
            mcpSuccess(
                mapOf(
                    "managerId" to managerId,
                    "managerName" to managerName,
                    "bindingIds" to bindings.map { it.id },
                    "bindings" to bindings.map { bindingView(it) }
                )
            )
        },

        // ── group_override (配置/清除分组出牌策略, 分步提交) ──
        // 分步语义（2026-08-10）：分组归分组（save_card_group 纯分组定义、保留已有策略）、
        // 卡组策略归策略（本工具 SET 策略 / clearOverride 清除）。追踪见 architecture-context/config-tooling/。
        typedTool<SaveGroupOverrideInput>(
            name = "group_override",
            description = """配置或清除分组的出牌阶段策略（stageOverride / conditionalStage）——分组定义与策略分步提交。
bindingId 由 card_group(action=GET) 的 bindings[].id 获取。
【清除】clearOverride=true：清除该分组的全部出牌策略（stageOverride/conditionalStage），保留其他行为（如 useActions），
返回 previousOverride（清除前的原始 override）——误清可用 SET 模式按此值恢复。缺省 false。
【配置】clearOverride=false（缺省）时：
stageOverride：覆盖出牌阶段（RESOURCE/SETUP/CLEAR/DEFEND/COMBO/GENERAL/END），缺省保留原值。
conditionalStage：条件化阶段——conditionalStageConditionId 或 conditionalStageConditionTreeJson（二选一）+
conditionalStageStage（必填）+ conditionalStageElseStage（可选）；条件树命中→conditionalStageStage，未命中→elseStage
（缺省沿用默认推导）。不提供 conditionalStage 相关字段则保留原值。
典型场景：莱妮莎/奥尔多侍从/斩星巨刃等引擎牌设 stageOverride=SETUP 使其优先打出；过牌与增幅牌顺序随手牌动态反转。"""
        ) { input ->
            val binding = groupService.loadAll()
                .flatMap { it.bindings }
                .firstOrNull { it.id == input.bindingId }
                ?: return@typedTool mcpError("分组绑定条目不存在: ${input.bindingId}")

            // ── clear mode：清除出牌策略（保留非 Override 行为），返回清除前原始 override 供误删恢复 ──
            if (input.clearOverride) {
                val previousOverride = binding.behaviors.findOverride()
                val newBehaviors = binding.behaviors.filterNot { it is CardGroupBehavior.OverrideBehavior }
                groupService.saveBinding(binding.copy(behaviors = newBehaviors))
                return@typedTool mcpSuccess(
                    mapOf(
                        "bindingId" to binding.id,
                        "bindingName" to binding.name,
                        "cleared" to true,
                        "previousOverride" to overrideView(previousOverride)
                    )
                )
            }

            val existingOverride = binding.behaviors.findOverride()

            // stageOverride：提供则解析覆盖，缺省保留原值
            val newStageOverride = input.stageOverride?.takeIf { it.isNotBlank() }?.let { s ->
                try {
                    UseStage.valueOf(s)
                } catch (_: IllegalArgumentException) {
                    throw McpBadInput("stageOverride 无效: '$s'，支持: ${UseStage.entries.joinToString { it.name }}")
                }
            } ?: existingOverride?.stageOverride

            // conditionalStage：提供完整输入则构建，缺省保留原值
            val hasConditionalInput = !input.conditionalStageConditionId.isNullOrBlank()
                    || !input.conditionalStageConditionTreeJson.isNullOrBlank()
            val newConditionalStage = if (hasConditionalInput) {
                val cid = resolveConditionTreeReference(
                    service = conditionTreeService,
                    mapper = conditionTreeMapper,
                    conditionId = input.conditionalStageConditionId?.takeIf { it.isNotBlank() },
                    treeJson = input.conditionalStageConditionTreeJson?.takeIf { it.isNotBlank() },
                    defaultName = "sort_${binding.name}_stage",
                    label = "binding[${binding.name}] conditionalStage 条件树",
                    managerId = binding.managerId
                )
                val stageName = input.conditionalStageStage?.takeIf { it.isNotBlank() }
                    ?: throw McpBadInput("提供 conditionalStage 时必须同时提供 conditionalStageStage")
                val stage = try {
                    UseStage.valueOf(stageName)
                } catch (_: IllegalArgumentException) {
                    throw McpBadInput("conditionalStageStage 无效: '$stageName'，支持: ${UseStage.entries.joinToString { it.name }}")
                }
                val elseStage = input.conditionalStageElseStage?.takeIf { it.isNotBlank() }?.let { elseStageName ->
                    try {
                        UseStage.valueOf(elseStageName)
                    } catch (_: IllegalArgumentException) {
                        throw McpBadInput("conditionalStageElseStage 无效: '$elseStageName'，支持: ${UseStage.entries.joinToString { it.name }}")
                    }
                }
                ConditionalStageOverride(conditionId = cid, stage = stage, elseStage = elseStage)
            } else {
                existingOverride?.conditionalStage
            }

            // 重建 behaviors：保留非 Override 行为（如 UseActionBehavior），替换 Override 为新值
            val newBehaviors = binding.behaviors
                .filterNot { it is CardGroupBehavior.OverrideBehavior } +
                    if (newStageOverride != null || newConditionalStage != null) {
                        listOf(
                            CardGroupBehavior.OverrideBehavior(
                                GroupUseOverride(
                                    stageOverride = newStageOverride,
                                    conditionalStage = newConditionalStage
                                )
                            )
                        )
                    } else {
                        emptyList()
                    }
            groupService.saveBinding(binding.copy(behaviors = newBehaviors))
            mcpSuccess(
                mapOf(
                    "bindingId" to binding.id,
                    "bindingName" to binding.name,
                    "stageOverride" to newStageOverride?.name,
                    "conditionalStage" to newConditionalStage?.let { cs ->
                        mapOf(
                            "conditionId" to cs.conditionId,
                            "stage" to cs.stage.name,
                            "elseStage" to cs.elseStage?.name
                        )
                    }
                )
            )
        }
    )
}

private data class SaveCardGroupInput(
    @field:JsonPropertyDescription("卡池来源文件名（不含扩展名）。正常模式必填；克隆模式下可省略（缺省使用克隆源的 sourceFile，也可显式覆盖）。")
    val sourceFile: String? = null,
    @field:JsonPropertyDescription("方案名称，缺省使用 sourceFile。cloneFrom 非空且不提供时自动加 \"副本\" 后缀。")
    val managerName: String? = null,
    @field:JsonPropertyDescription("已有方案的 id（由 card_group(action=LIST) 获取），非空则更新该方案。与 cloneFrom 互斥。")
    val existingId: String? = null,
    @field:JsonPropertyDescription("分组列表（仅分组定义：name/cardIds/description）。cloneFrom 非空时忽略此字段（使用克隆源的 binding）。出牌策略用 save_group_override 单独配置。")
    val bindings: List<SaveCardGroupBindingInput> = emptyList(),
    @field:JsonPropertyDescription("克隆已有分组方案的 id（由 card_group(action=LIST) 获取）。与 existingId 互斥；提供 cloneFrom 时 sourceFile/bindings 可省略。非空时以该方案为蓝本创建副本，含所有 binding 与 behavior。")
    val cloneFrom: String? = null,
    @field:JsonPropertyDescription("可选：卡组总体描述/规划（战术主题、配置目标），供续接时恢复上下文。")
    val managerDescription: String? = null,
    @field:JsonPropertyDescription("可选：配置进度状态（PLANNED=已规划未开始 / IN_PROGRESS=配置中 / CONFIGURED=已完成可运行）。缺省不覆盖原值。")
    val managerStatus: String? = null
)

private data class SaveCardGroupBindingInput(
    @field:JsonPropertyDescription("分组名称")
    val name: String,
    @field:JsonPropertyDescription("该分组包含的卡牌 ID 列表")
    val cardIds: List<String>,
    @field:JsonPropertyDescription("分组说明（战术定位/联动动机）。出牌策略（stageOverride/conditionalStage）由 save_group_override 单独配置。")
    val description: String? = null
)

private data class SaveGroupOverrideInput(
    @field:JsonPropertyDescription("分组绑定条目 id（card_group(action=GET) 的 bindings[].id）。")
    val bindingId: String,
    @field:JsonPropertyDescription("可选：true 时清除该分组的全部出牌策略（stageOverride/conditionalStage），保留其他行为（如 useActions）。缺省 false。")
    val clearOverride: Boolean = false,
    @field:JsonPropertyDescription("可选：覆盖出牌阶段（RESOURCE/SETUP/CLEAR/DEFEND/COMBO/GENERAL/END）。缺省保留原值。")
    val stageOverride: String? = null,
    @field:JsonPropertyDescription("可选：条件化出牌阶段的条件树 id（condition_tree(action=LIST) 获取）。与 conditionalStageConditionTreeJson 互斥。")
    val conditionalStageConditionId: String? = null,
    @field:JsonPropertyDescription("可选：条件化出牌阶段的条件树内联 JSON（一次性树，无需先建模板）：完整条件树 JSON 文本 {id,name,root}。与 conditionalStageConditionId 互斥。")
    val conditionalStageConditionTreeJson: String? = null,
    @field:JsonPropertyDescription("条件化阶段命中时用的出牌阶段（UseStage 枚举值，提供 conditionalStage 时必填）。")
    val conditionalStageStage: String? = null,
    @field:JsonPropertyDescription("可选：条件化阶段未命中时用的出牌阶段（UseStage 枚举值），缺省沿用默认推导。")
    val conditionalStageElseStage: String? = null
)
