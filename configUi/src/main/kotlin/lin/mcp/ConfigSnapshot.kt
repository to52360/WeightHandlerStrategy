package lin.mcp

import lin.bean.usePlan.PurposeTagIntentRule
import lin.bean.usePlan.UseStage
import lin.dao.CardGroupConfig
import lin.dao.CardGroupJsonParser
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.CardManagerEntity
import lin.repository.card_purpose.CardPurposeRepository
import lin.repository.combo_plan.ComboPlanDefinitionEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.rule.condition.ConditionPayload
import lin.rule.condition.collectConditionRefs
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.rule.tree.EvaluatorTreeBindingType
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider
import lin.ui.service.TreeConfigService

/**
 * 只读配置快照：**一次装配，多个只读面共享**（现在的 `strategy_coverage` / `strategy_diagnostics`，
 * 以及后续任何"读配置做判断"的工具）。
 *
 * ## 为什么要有它
 * 每个只读工具各自读库、各自解析同一批来源 ⇒ 同一份配置被解析 N 遍，且**每加一个只读面就多一份
 * 重复的读取代码**（改一处口径要记得改 N 处，漏改即静默不一致）。快照把「怎么读」收成一份，
 * 只读面只消费结果。装配点唯一：[ConfigSnapshotAssembler.assemble]。
 *
 * ## 字段语义
 * - [managers] / [managerMeta] / [cardPools]：**不按 managerId 过滤**（全量），由消费方自己选范围
 *   （覆盖侧按 managerId 选、体检侧按"启用与否"选，两套选法各有理由，不硬塞进装配层）。
 * - [treeByBinding] / [cardTreeByCard]：GROUP / CARD 绑定的树摘要（`id` + `name`）。
 * - [comboByGroup] / [boostByGroup]：**按 `managerId` 过滤**（combo/aura 是卡组从属资源）——
 *   传 null = 全部。
 * - [bindingsById]：分组 id → 分组本体（覆盖来源需按 id 回查行为声明）。
 * - [tagIntentByTag]：标签 → 默认意图（stage / orderWeight），来源 = `purpose_tag_rule` 表。
 */
class ConfigSnapshot(
    val managers: List<CardGroupManagerConfig>,
    val bindingsById: Map<String, CardGroupBinding>,
    val managerMeta: Map<String, CardManagerEntity>,
    val cardPools: Map<String, CardGroupConfig>,
    val tagsByCard: Map<String, List<String>>,
    val treeByBinding: Map<String, List<Map<String, String>>>,
    val cardTreeByCard: Map<String, List<Map<String, String>>>,
    val comboByGroup: Map<String, List<Pair<ComboPlanDefinitionEntity, String>>>,
    val boostByGroup: Map<String, List<Pair<AuraBoostEntity, String>>>,
    val tagIntentByTag: Map<String, TagIntent>
)

/**
 * 标签的默认意图摘要（快照层的小值类：只带**时序**相关字段，不把整条引擎规则搬过来）。
 *
 * 之所以在快照层再包一层而不是直接持 [PurposeTagIntentRule]：只读工具需要的是
 * 「这个标签默认排在哪一段、排序权重多少」，其余字段（priority / replan / N）各有专职来源，
 * 混在一起会让消费方误用（例如用标签的 N 去当生效 N——那是体检侧跨三层解析后的结果）。
 */
data class TagIntent(val stage: UseStage, val orderWeight: Double)

/**
 * 快照装配器（**单点**）：把 6 类配置来源读一遍并解析成 [ConfigSnapshot]。
 *
 * 注入方式 = Koin `single`（与两个只读 Provider 共享同一实例）；**不是** `getAll<ConfigSnapshot>()`
 * 之类的容器列表（`D-FO-009` 教训：同类型 + 无条件符的容器列表会互相覆盖、静默失效）。
 */
class ConfigSnapshotAssembler(
    private val cardGroupService: CardGroupService,
    private val treeConfigService: TreeConfigService,
    private val comboPlanRepository: ComboPlanDefinitionRepository,
    private val auraBoostService: AuraBoostConfigService,
    private val conditionTreeService: ConditionTreeConfigService,
    private val cardPurposeRepository: CardPurposeRepository,
    private val purposeTagIntentRuleProvider: PurposeTagIntentRuleProvider
) {

    /**
     * 装配快照。
     *
     * @param managerId 只影响 [ConfigSnapshot.comboByGroup] / [ConfigSnapshot.boostByGroup] 的范围
     *   （卡组从属资源）；null = 全部卡组。其余来源始终全量。
     */
    fun assemble(managerId: String? = null): ConfigSnapshot {
        val managers = cardGroupService.loadAll()

        // 评估树：GROUP 绑定条目 id -> 树摘要（含全局共享树）
        val treeByBinding = treeConfigService.loadAll()
            .mapNotNull { (entity, config) -> config?.let { entity to it } }
            .filter { (_, config) -> config.bindingType == EvaluatorTreeBindingType.GROUP }
            .flatMap { (entity, config) -> config.bindingIds.map { bid -> bid to entity } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.map { mapOf("id" to it.id, "name" to it.name) }.distinct() }

        // 评估树：CARD 绑定单卡树 cardId -> 树摘要（CARD 树是全局资源，按卡池归属展示）
        val cardTreeByCard = treeConfigService.loadAll()
            .mapNotNull { (entity, config) -> config?.let { entity to it } }
            .filter { (_, config) -> config.bindingType == EvaluatorTreeBindingType.CARD }
            .flatMap { (entity, config) -> config.bindingIds.map { cardId -> cardId to entity } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.map { mapOf("id" to it.id, "name" to it.name) }.distinct() }

        // combo：groupId -> [(combo, role)]（core / dep）
        val comboByGroup = mutableMapOf<String, MutableList<Pair<ComboPlanDefinitionEntity, String>>>()
        comboPlanRepository.findAll()
            .filter { managerId == null || it.managerId == managerId }
            .forEach { c ->
                c.coreGroupIds.split(",").filter { it.isNotBlank() }
                    .forEach { gid -> comboByGroup.getOrPut(gid) { mutableListOf() }.add(c to "core") }
                c.depGroupIds.split(",").filter { it.isNotBlank() }
                    .forEach { gid -> comboByGroup.getOrPut(gid) { mutableListOf() }.add(c to "dep") }
            }

        // AuraBoost：条件树内 group_filter 引用 -> groupId -> boost（via = condition / target）
        val boostByGroup = mutableMapOf<String, MutableList<Pair<AuraBoostEntity, String>>>()
        auraBoostService.loadAll()
            .filter { managerId == null || it.managerId == managerId }
            .forEach { boost ->
                extractGroupFilterIds(boost.conditionId).forEach { gid ->
                    boostByGroup.getOrPut(gid) { mutableListOf() }.add(boost to "condition")
                }
                extractGroupFilterIds(boost.targetConditionId).forEach { gid ->
                    boostByGroup.getOrPut(gid) { mutableListOf() }.add(boost to "target")
                }
            }

        // 用途标签：cardId -> tags
        val tagsByCard = cardPurposeRepository.findAll()
            .associateBy({ it.cardId }) { entity ->
                entity.purposeTags.split(",").map(String::trim).filter { it.isNotBlank() }
            }

        return ConfigSnapshot(
            managers = managers,
            bindingsById = managers.flatMap { it.bindings }.associateBy { it.id },
            managerMeta = cardGroupService.loadAllManagers().associateBy { it.id },
            cardPools = CardGroupJsonParser.loadAllCardGroups().toMap(),
            tagsByCard = tagsByCard,
            treeByBinding = treeByBinding,
            cardTreeByCard = cardTreeByCard,
            comboByGroup = comboByGroup,
            boostByGroup = boostByGroup,
            // 标签默认意图（`purpose_tag_rule`，SPI 读，与引擎 UseIntentDeriver 同源）；
            // 表为空 = 「未声明 = 无规则」的合法终态（T-TG-008）
            tagIntentByTag = purposeTagIntentRuleProvider.rules()
                .associate { rule -> rule.tagId.value to TagIntent(rule.defaultStage, rule.defaultOrderWeight) }
        )
    }

    /** 解析条件树内 group_filter transform 引用的分组 id（AuraBoost→分组 的覆盖判定）。 */
    private fun extractGroupFilterIds(conditionTreeId: String): Set<String> {
        val config = conditionTreeService.findById(conditionTreeId) ?: return emptySet()
        return config.root.collectConditionRefs()
            .asSequence()
            .filterIsInstance<ConditionPayload.PipelineRef>()
            .flatMap { ref -> ref.transforms.asSequence() }
            .filter { it.transformId == "group_filter" }
            .mapNotNull { it.args["groupId"] as? String }
            .toSet()
    }
}
