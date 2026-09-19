package lin.ui.components.action

import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.stage.Window

/**
 * 泛型资源选择与操作栏（ComboBox<T> + 动态能力动作按钮组）
 *
 * 架构定位：展现与解析层
 * 1. 消费能力值：接收 [capabilities] 列表；
 * 2. 多态派发：禁用状态由 [UiCapability.isEnabled] 多态决定，按钮点击由 [UiCapability.execute] 多态派发，
 *    内部杜绝任何基于 boolean 标志位或模式枚举的 if-else 硬编码判断；
 * 3. 响应式驱动：监听 ComboBox 选项变更，驱动各能力重新计算状态。
 */
class ResourcePickerBar<T>(
    promptText: String = "请选择...",
    val capabilities: List<UiCapability<T>> = emptyList()
) : HBox(8.0) {

    val comboBox = ComboBox<T>().apply {
        this.promptText = promptText
        maxWidth = Double.MAX_VALUE
    }

    private val actionButtons = mutableListOf<Pair<UiCapability<T>, Button>>()

    private val context = object : PickerContext<T> {
        override val selectedItem: T? get() = comboBox.value
        override fun select(item: T?) { comboBox.value = item }
        override val window: Window? get() = scene?.window
    }

    init {
        alignment = Pos.CENTER_LEFT
        HBox.setHgrow(comboBox, Priority.ALWAYS)
        children.add(comboBox)

        // 解析层：根据能力值元数据构建按钮，执行全走多态派发（零 if 判断）
        capabilities.forEach { cap ->
            val btn = Button(cap.label).apply {
                style = cap.variant.cssStyle
                minWidth = Region.USE_PREF_SIZE
                setOnAction { cap.execute(context) }
            }
            actionButtons.add(cap to btn)
            children.add(btn)
        }

        // 解析层：监听选中项变化，驱动多态重新计算可用性（零 if 判断）
        comboBox.valueProperty().addListener { _, _, _ ->
            updateButtonsState()
        }
        updateButtonsState()
    }

    /** 更新所有能力按钮的可用状态（多态计算） */
    fun updateButtonsState() {
        val currentItem = comboBox.value
        actionButtons.forEach { (cap, btn) ->
            btn.isDisable = !cap.isEnabled(currentItem)
        }
    }

    /** 当前选中的实体项 */
    var selectedItem: T?
        get() = comboBox.value
        set(value) {
            comboBox.value = value
            updateButtonsState()
        }

    /** 设置下拉候选列表，默认尝试保留当前已选中的实体 */
    fun setItems(items: List<T>, retainSelection: Boolean = true) {
        val current = selectedItem
        comboBox.items.setAll(items)
        if (retainSelection && current != null) {
            val matched = items.find { it == current }
            comboBox.value = matched
        }
        updateButtonsState()
    }

    /** 根据条件谓词查找并选中匹配项 */
    fun selectMatching(predicate: (T) -> Boolean) {
        val matched = comboBox.items.find(predicate)
        comboBox.value = matched
        updateButtonsState()
    }

    /** 清空当前选择 */
    fun clearSelection() {
        comboBox.value = null
        updateButtonsState()
    }
}
