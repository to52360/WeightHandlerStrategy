package lin.ui.card_purpose

import lin.bean.usePlan.PurposeTagId
import lin.repository.card_purpose.PurposeTagDefRepository

/**
 * 数据库驱动的标记目录（T-TG-001），替代硬编码的 [DefaultPurposeTagProvider]。
 *
 * 换实现后下列消费者**零改动**自动支持自定义标记 —— 因为它们都只读 [PurposeTagProvider.tags()]：
 * MCP `purpose_tag` 的 list/get、`CardPurposeWorkbench` 打标下拉、
 * [PurposeTagTreeBindingPolicy]（可绑评估树）、[PurposeTagProvider.displayName]。
 *
 * 保留 [DefaultPurposeTagProvider] 作为无库场景的 fallback（如单元测试直接构造）。
 */
class SqlitePurposeTagProvider(
    private val repository: PurposeTagDefRepository
) : PurposeTagProvider {

    override fun tags(): List<PurposeTagDef> =
        repository.findAll().map { PurposeTagDef(PurposeTagId(it.tagId), it.displayName) }
}
