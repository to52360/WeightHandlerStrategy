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
    private val tagProvider: PurposeTagProvider
) {
    /**
     * 每次访问重算（T-TG-001）：标记定义落库后支持运行时登记，
     * 构造期快照会让新登记的标记必须重启才可绑评估树。
     *
     * ⚠️ 调用方须在一次加载内复用结果（见 `SqliteTreeConfigProvider.filterBindings`），
     * 否则会退化为逐绑定查库。
     */
    val enabledTags: Set<PurposeTagId>
        get() = tagProvider.tags().map { it.id }.toSet()
}
