package lin.ui.service

import lin.repository.card_group.PresetSummary
import lin.repository.card_group.StrategyPresetService
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
     * 拥有全局规则行的用途列表（按 priority 排序）。
     *
     * ⚠️ **时序面板与惜售面板共用**这份候选（T-TG-029 起两者是并列维度）：
     * 它既决定"哪些用途可声明"，也充当两个面板的**默认值提示来源**。
     */
    val timingRules: List<PurposeTagRuleEntity>,
    /** 库中已启用的全局用途标签全集（禁用用途看板口径，D-TG-015） */
    val purposeUniverse: Set<String>
)

/**
 * 预设域目录装配器（T-TG-024 能力的单点装配口）。
 *
 * 收敛「候选树 + 时序规则 + 用途全集 + 预设列表」的装配逻辑：
 * 消费方 Store 只依赖本装配器 + 领域服务，不再各自持有 TreeConfigService /
 * PurposeTagRuleRepository 两个仅在装配期使用的依赖（防中转依赖扩散）。
 */
class PresetCatalogLoader(
    private val presetService: StrategyPresetService,
    private val treeConfigService: TreeConfigService,
    private val ruleRepository: PurposeTagRuleRepository
) {
    fun load(): PresetCatalog = PresetCatalog(
        presets = presetService.listPresets(),
        candidateTrees = treeConfigService.findGlobalSummaries(
            EvaluatorTreeBindingType.PURPOSE_TAG,
            enabledOnly = false
        ),
        timingRules = ruleRepository.findAll().sortedBy { it.priority },
        purposeUniverse = treeConfigService.purposeTagUniverse()
    )
}
