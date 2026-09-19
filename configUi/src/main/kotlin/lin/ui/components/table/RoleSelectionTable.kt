package lin.ui.components.table

import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView

/**
 * 角色/多维度分类列纯数据声明（能力值化，函数作为值）
 */
data class RoleColumnSpec<T>(
    val roleId: String,
    val title: String,
    val width: Double = 60.0,
    val isSelected: (item: T) -> Boolean,
    val onToggle: (item: T, selected: Boolean) -> Unit
)

/**
 * 通用多角色/多标签勾选表格组件：
 * 支持任意实体类型 T 进行左侧名称展示与右侧多列 CheckBox 角色勾选。
 * 彻底消除特定领域的 UiRow 中间胶水类与基于 rowIndex 寻址的脆弱反模式。
 */
class RoleSelectionTable<T>(
    nameTitle: String = "名称",
    nameWidth: Double = 190.0,
    nameExtractor: (item: T) -> String,
    roleColumns: List<RoleColumnSpec<T>>,
    prefTableHeight: Double = 350.0
) : TableView<T>() {

    private val obsItems = FXCollections.observableArrayList<T>()

    init {
        prefHeight = prefTableHeight
        columnResizePolicy = UNCONSTRAINED_RESIZE_POLICY

        // 1. 实体名称展示列
        val colName = TableColumn<T, String>(nameTitle).apply {
            setCellValueFactory { SimpleStringProperty(nameExtractor(it.value)) }
            prefWidth = nameWidth
        }
        columns.add(colName)

        // 2. 动态角色勾选列（通过类型安全的 TableCell 绑定 item）
        for (spec in roleColumns) {
            val roleCol = TableColumn<T, T>(spec.title).apply {
                setCellValueFactory { ReadOnlyObjectWrapper(it.value) }
                setCellFactory { RoleCheckBoxCell(spec) }
                prefWidth = spec.width
                style = "-fx-alignment: CENTER;"
            }
            columns.add(roleCol)
        }

        items = obsItems
    }

    /** 设置并刷新数据项 */
    fun setItems(newItems: List<T>) {
        obsItems.setAll(newItems)
        refresh()
    }

    /** 清空表格数据 */
    fun clear() {
        obsItems.clear()
    }
}

/**
 * 安全的 CheckBox TableCell 实现：
 * 直接与绑定的 item: T 通信，绝不依赖脆弱的 items[rowIndex] 数组下标。
 */
private class RoleCheckBoxCell<T>(
    private val spec: RoleColumnSpec<T>
) : TableCell<T, T>() {

    private val checkBox = CheckBox()
    private var isUpdating = false

    init {
        alignment = Pos.CENTER
        checkBox.setOnAction {
            val currentItem = item ?: return@setOnAction
            if (!isUpdating) {
                spec.onToggle(currentItem, checkBox.isSelected)
            }
        }
    }

    override fun updateItem(item: T?, empty: Boolean) {
        super.updateItem(item, empty)
        if (empty || item == null) {
            graphic = null
        } else {
            isUpdating = true
            try {
                checkBox.isSelected = spec.isSelected(item)
            } finally {
                isUpdating = false
            }
            graphic = checkBox
        }
    }
}
