package lin.ui.strategy_preset

import lin.repository.card_group.PresetDetail
import lin.repository.card_group.PresetSummary
import lin.repository.card_purpose.PurposeTagRuleEntity
import lin.repository.tree_config.TreeConfigEntity

/**
 * 策略预设工作台不可变状态封装（T-TG-016 / T-TG-017）。
 *
 * 字段 > 4，严格遵循项目状态封装规范。
 */
data class StrategyPresetState(
    /** 全部预设摘要列表 */
    val allPresets: List<PresetSummary> = emptyList(),
    /** 经关键词过滤后的展示列表 */
    val filteredPresets: List<PresetSummary> = emptyList(),
    /** 当前选中的预设 ID */
    val selectedPresetId: String? = null,
    /** 当前选中的预设详情（包含两个维度的声明详情） */
    val selectedDetail: PresetDetail? = null,
    /** 搜索关键词 */
    val searchText: String = "",
    /** 顶部当前选中的卡组 ID（仅用于高亮"当前卡组在用"的预设，不持续订阅） */
    val activeDeckId: String? = null,
    /** 是否处于新建模式（未保存前清空详情区） */
    val isCreating: Boolean = false,
    /** 全局共享用途树候选列表（manager_id IS NULL 且 bindingType = PURPOSE_TAG） */
    val candidateTrees: List<TreeConfigEntity> = emptyList(),
    /** 拥有全局规则行的用途列表（可覆盖时序） */
    val timingRules: List<PurposeTagRuleEntity> = emptyList(),
    /** 库中已启用的全局用途标签全集（用于计算禁用用途看板） */
    val purposeUniverse: Set<String> = emptySet(),
    /** 标签显示名字典（tag_id -> display_name，数据源自 purpose_tag_def 表） */
    val tagDisplayNames: Map<String, String> = emptyMap()
)
