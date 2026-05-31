package lin.domain.use.plan

import lin.bean.ComboCard
import lin.bean.usePlan.CardComboUseBinding
import lin.bean.usePlan.UseIntent

/**
 * use.plan 领域自己的配置读取入口。
 *
 * 这些字段只服务出牌编排，不放回 ComboCard 本体，避免运行时状态容器继续膨胀。
 */
internal fun ComboCard.useIntent(): UseIntent = combinedConfig?.useIntent ?: UseIntent()

internal fun ComboCard.comboUseBindings(): List<CardComboUseBinding> = combinedConfig?.comboUseBindings ?: emptyList()
