package lin.ui.service

import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.card_group.PresetSummary
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_purpose.PurposeTagDefEntity
import lin.repository.card_purpose.PurposeTagDefRepository
import lin.repository.card_purpose.PurposeTagRuleEntity
import lin.repository.card_purpose.PurposeTagRuleRepository
import lin.repository.tree_config.TreeConfigEntity
import lin.rule.tree.EvaluatorTreeBindingType

/**
 * 预设域目录数据（编辑 UI 的一次性装配快照）。
 *
 * 供策略预设工作台与卡组工作台的候选展示使用：预设列表、全局用途树候选、
 * 可覆盖时序规则、用途全集 —— 四者总是协同加载，封装为单一目录对象。
 */
data class PresetCatalog(
    /** 全部预设摘要（含维度计数与引用者） */
    val presets: List<PresetSummary>,
    /** 全局共享用途树候选（manager_id IS NULL 且 PURPOSE_TAG，含禁用项） */
    val candidateTrees: List<TreeConfigEntity>,
    /**
     * 可声明的「作用」及其**默认值提示**（按 priority 排序；D-DP-004）。
     *
     * ⚠️ **候选 = 内置能力清单 ∪ 库中晋级行**（见 `PresetCatalogLoader.declarableRules`），
     * **不拿规则表行当全集** —— 表行只提供默认值（可被改、可缺失，缺失时回落内置种子）
     * ⇒ 候选集不随业务值表缩水。
     * ⚠️ **时序面板与惜售面板共用**这份候选（T-TG-029 起两者是并列维度）。
     */
    val timingRules: List<PurposeTagRuleEntity>,
    /** 库中已启用的全局用途标签全集（禁用用途看板口径，D-TG-015） */
    val purposeUniverse: Set<String>,
    /** 标签显示名字典（tag_id -> display_name，单一数据源自 purpose_tag_def 表） */
    val tagDisplayNames: Map<String, String> = emptyMap(),
    /** **全局光环行**候选（D-DP-001：只有 `manager_id IS NULL` 的行可被预设白名单 / 卡组增量项引用）。 */
    val candidateAuraBoosts: List<AuraBoostOption> = emptyList()
)

/** 光环候选（id + 名称；候选恒为全局行，`managerId` 保留字段供展示与断言）。 */
data class AuraBoostOption(val id: String, val name: String, val managerId: String? = null)

/**
 * 预设域目录装配器（T-TG-024 能力的单点装配口）。
 *
 * 收敛「候选树 + 时序规则 + 用途全集 + 预设列表 + 标签显示名」的装配逻辑：
 * 消费方 Store 只依赖本装配器 + 领域服务，不再各自持有 TreeConfigService /
 * PurposeTagRuleRepository 两个仅在装配期使用的依赖（防中转依赖扩散）。
 */
class PresetCatalogLoader(
    private val presetService: StrategyPresetService,
    private val treeConfigService: TreeConfigService,
    private val ruleRepository: PurposeTagRuleRepository,
    private val tagDefRepository: PurposeTagDefRepository? = null,
    /** D-DP-001：全局光环行候选（预设「光环声明」区的勾选来源）。 */
    private val auraBoostService: AuraBoostConfigService? = null
) {
    fun load(): PresetCatalog = PresetCatalog(
        presets = presetService.listPresets(),
        candidateTrees = treeConfigService.findGlobalSummaries(
            EvaluatorTreeBindingType.PURPOSE_TAG,
            enabledOnly = false
        ),
        timingRules = declarableRules(),
        purposeUniverse = treeConfigService.purposeTagUniverse(),
        tagDisplayNames = tagDefRepository?.findAll()?.associate { it.tagId to it.displayName }.orEmpty(),
        // 只有**全局行**可被预设白名单引用（私有行归属其卡组，引用对其他卡组静默无效）
        candidateAuraBoosts = auraBoostService?.loadAll()
            .orEmpty()
            .filter { it.managerId == null }
            .map { AuraBoostOption(id = it.id, name = it.name ?: it.id, managerId = it.managerId) }
    )

    /**
     * 可声明的「作用」+ 默认值提示（D-DP-004：内置能力清单 ∪ 库中晋级行）。
     *
     * - 内置侧逐条取「表行 > 内置种子」：表行是**业务值**（可被改、可缺失），种子是**能力**（恒在）——
     *   若拿表行当候选，删一行就少一个候选项，而 UI 保存是维度级整体替换 ⇒ 会静默抹掉已有声明。
     * - 自定义侧取 `declarable = 1` 的晋级行（与 MCP 守门 `declarableTagIds()` 同源口径）；晋级行通常
     *   还没有全局规则表行 ⇒ 用 `GENERAL / priority 100` 的默认**提示**实体（声明模型下"未声明 = 不生效"）。
     */
    private fun declarableRules(): List<PurposeTagRuleEntity> {
        val stored = ruleRepository.findAll().associateBy { it.tagId }
        val builtin = PurposeTagRuleRepository.BUILTIN_RULES.map { seed -> stored[seed.tagId] ?: seed }
        val promoted = tagDefRepository?.findAll()
            .orEmpty()
            .filter { !it.builtin && it.declarable }
            .map { def -> stored[def.tagId] ?: defaultRuleHint(def) }
        return (builtin + promoted).sortedBy { it.priority }
    }

    /** 晋级行无全局规则表行时的默认值提示实体（与声明模型的内置默认一致：GENERAL / priority 100）。 */
    private fun defaultRuleHint(def: PurposeTagDefEntity) = PurposeTagRuleEntity(
        tagId = def.tagId,
        defaultStage = "GENERAL",
        priority = 100
    )
}
