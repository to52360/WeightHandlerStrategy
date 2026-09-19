package lin.ui.components.action

import javafx.stage.Window

/**
 * 语义化动作样式变体（统一全项目设计规范，杜绝散落十六进制色值）
 */
enum class ActionVariant(val cssStyle: String) {
    PRIMARY("-fx-background-color: #0d6efd; -fx-text-fill: white; -fx-font-size: 11px;"),
    SUCCESS("-fx-background-color: #198754; -fx-text-fill: white; -fx-font-size: 11px;"),
    SECONDARY("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-size: 11px;"),
    DANGER("-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-size: 11px;"),
    OUTLINE("-fx-background-color: #f8f9fa; -fx-border-color: #ced4da; -fx-text-fill: #495057; -fx-font-size: 11px;")
}

/**
 * 交互上下文：供各能力执行或读取状态时与宿主选择器通信
 */
interface PickerContext<T> {
    /** 当前选中的项/实体（未选时为 null） */
    val selectedItem: T?

    /** 修改当前选中的项 */
    fun select(item: T?)

    /** 宿主窗口（供弹窗定位） */
    val window: Window?
}

/**
 * UI 能力契约（纯数据值，能力由类型结构表达，杜绝 boolean 标志位与 if 判断模式）
 *
 * 核心设计：
 * 1. 消除用类做粘合剂：不为具体业务建空壳类，能力即持有 Lambda 的函数值；
 * 2. 消除 if 模式判断：通过正交子类型（独立动作 StandaloneCapability vs 实体动作 EntityCapability）表达多态，
 *    可用性与执行逻辑均由类型多态承载，解析层零 if-else。
 */
sealed interface UiCapability<T> {
    val label: String
    val variant: ActionVariant

    /** 可用性判定：由类型多态表达，解析层无需 if 判断 */
    fun isEnabled(selectedItem: T?): Boolean

    /** 执行派发：由类型多态表达，解析层无需 if 判断 */
    fun execute(context: PickerContext<T>)
}

/**
 * 独立动作能力：不依赖实体选中状态（如新建），始终可用
 */
data class StandaloneCapability<T>(
    override val label: String,
    override val variant: ActionVariant = ActionVariant.SUCCESS,
    val handle: (context: PickerContext<T>) -> Unit
) : UiCapability<T> {
    override fun isEnabled(selectedItem: T?): Boolean = true
    override fun execute(context: PickerContext<T>) = handle(context)
}

/**
 * 实体动作能力：强依赖当前选中的实体（如编辑、预览、清空），未选中实体时自动不可用
 * 类型签名强制要求传入非空 item: T，杜绝了在无实体状态下的执行可能
 */
data class EntityCapability<T>(
    override val label: String,
    override val variant: ActionVariant = ActionVariant.PRIMARY,
    val handle: (item: T, context: PickerContext<T>) -> Unit
) : UiCapability<T> {
    override fun isEnabled(selectedItem: T?): Boolean = selectedItem != null

    override fun execute(context: PickerContext<T>) {
        val item = context.selectedItem ?: return
        handle(item, context)
    }
}

/**
 * 能力值标准工厂（纯便捷函数，不制造额外类）
 */
object UiCapabilities {

    /** 构造独立动作能力（如新建） */
    fun <T> create(
        label: String = "新建",
        handle: (context: PickerContext<T>) -> Unit
    ): UiCapability<T> = StandaloneCapability(
        label = label,
        variant = ActionVariant.SUCCESS,
        handle = handle
    )

    /** 构造实体编辑动作能力 */
    fun <T> edit(
        label: String = "编辑",
        handle: (item: T, context: PickerContext<T>) -> Unit
    ): UiCapability<T> = EntityCapability(
        label = label,
        variant = ActionVariant.PRIMARY,
        handle = handle
    )

    /** 构造实体预览/查看动作能力 */
    fun <T> inspect(
        label: String = "预览",
        handle: (item: T, context: PickerContext<T>) -> Unit
    ): UiCapability<T> = EntityCapability(
        label = label,
        variant = ActionVariant.SECONDARY,
        handle = handle
    )

    /** 构造清空选择动作能力 */
    fun <T> clear(label: String = "清空"): UiCapability<T> = EntityCapability(
        label = label,
        variant = ActionVariant.OUTLINE,
        handle = { _, ctx -> ctx.select(null) }
    )
}
