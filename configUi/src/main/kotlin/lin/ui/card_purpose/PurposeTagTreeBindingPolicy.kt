package lin.ui.card_purpose

import lin.bean.usePlan.PurposeTagId

/**
 * 用途标签评估树绑定全局开关。
 *
 * 控制哪些 [PurposeTagId] 允许参与评估树绑定。
 * 默认开放所有 [PurposeTagProvider] 返回的标签，后续可持久化到 SQLite 或通过 UI 配置。
 * 由 [lin.provider.SqliteTreeConfigProvider] 在 SPI 边界消费。
 */
class PurposeTagTreeBindingPolicy(
    tagProvider: PurposeTagProvider
) {
    val enabledTags: Set<PurposeTagId> = tagProvider.tags().map { it.id }.toSet()
}
