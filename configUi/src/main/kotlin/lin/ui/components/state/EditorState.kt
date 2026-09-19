package lin.ui.components.state

import javafx.beans.binding.BooleanBinding
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleObjectProperty
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Label
import javafx.scene.layout.HBox

/**
 * 编辑面板的领域生命周期多态状态（纯数据值，类型多态，杜绝 Boolean/int 标志位与 if 判断）
 */
sealed interface EditorState<out T> {
    /** 空态/未选中：无实体处于编辑中 */
    data class Empty(val placeholder: String = "未选择配置") : EditorState<Nothing>

    /** 新建草稿态：正在新建某种实体 */
    data class Creating(val title: String = "新建配置") : EditorState<Nothing>

    /** 编辑实体态：持有当前编辑的领域实体 */
    data class Editing<T>(
        val entity: T,
        val title: String,
        val badge: String? = null
    ) : EditorState<T>
}

/**
 * 声明式状态驱动标题栏：
 * 监听 EditorState<T> 自动响应渲染视觉色调与 Badge，并暴露基于状态的只读只写属性供外部按需联动。
 */
class EditorHeaderBar<T>(
    isCentered: Boolean = true
) : HBox(8.0) {

    val stateProperty = SimpleObjectProperty<EditorState<T>>(EditorState.Empty())
    var state: EditorState<T>
        get() = stateProperty.get()
        set(value) = stateProperty.set(value)

    private val titleLabel = Label().apply {
        style = "-fx-font-size: 16px; -fx-font-weight: bold;"
    }
    private val badgeLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-padding: 2 6; -fx-background-radius: 4; -fx-text-fill: white;"
        isVisible = false
    }

    /** 派生属性：是否处于空态禁用中（当且仅当 state is Empty 时为 true） */
    val isEditingDisabled: BooleanBinding = Bindings.createBooleanBinding(
        { stateProperty.get() is EditorState.Empty },
        stateProperty
    )

    /** 派生属性：删除按钮是否可见（当且仅当 state is Editing 时为 true） */
    val isDeleteVisible: BooleanBinding = Bindings.createBooleanBinding(
        { stateProperty.get() is EditorState.Editing },
        stateProperty
    )

    init {
        alignment = if (isCentered) Pos.CENTER else Pos.CENTER_LEFT
        padding = Insets(0.0, 0.0, 5.0, 0.0)
        children.addAll(titleLabel, badgeLabel)

        stateProperty.addListener { _, _, newState -> renderState(newState) }
        renderState(stateProperty.get())
    }

    private fun renderState(state: EditorState<T>) {
        when (state) {
            is EditorState.Empty -> {
                titleLabel.text = state.placeholder
                titleLabel.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #7f8c8d;"
                badgeLabel.isVisible = false
            }
            is EditorState.Creating -> {
                titleLabel.text = state.title
                titleLabel.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #198754;"
                badgeLabel.text = "新建"
                badgeLabel.style = "-fx-font-size: 11px; -fx-padding: 2 6; -fx-background-radius: 4; -fx-text-fill: white; -fx-background-color: #198754;"
                badgeLabel.isVisible = true
            }
            is EditorState.Editing -> {
                titleLabel.text = state.title
                titleLabel.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;"
                if (!state.badge.isNullOrBlank()) {
                    badgeLabel.text = state.badge
                    badgeLabel.style = "-fx-font-size: 11px; -fx-padding: 2 6; -fx-background-radius: 4; -fx-text-fill: white; -fx-background-color: #0d6efd;"
                    badgeLabel.isVisible = true
                } else {
                    badgeLabel.isVisible = false
                }
            }
        }
    }

    fun showEmpty(placeholder: String = "未选择配置") {
        state = EditorState.Empty(placeholder)
    }

    fun showCreating(title: String = "新建配置") {
        state = EditorState.Creating(title)
    }

    fun showEditing(entity: T, title: String, badge: String? = null) {
        state = EditorState.Editing(entity, title, badge)
    }
}
