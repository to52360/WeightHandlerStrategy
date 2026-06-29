package lin.ui.combo_plan.ui

import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.util.Callback
import lin.rule.tree.CardGroupBinding

/**
 * 界面表格行模型：单行绑定及对应的核心/依赖状态数据绑定
 */
class BindingUiRow(
    val binding: CardGroupBinding,
    val name: String,
    val coreProperty: SimpleBooleanProperty = SimpleBooleanProperty(false),
    val depProperty: SimpleBooleanProperty = SimpleBooleanProperty(false)
)

/**
 * 高内聚、类型安全且高性能的复选框列自定义 Cell 渲染工厂
 * 避免了 JavaFX TableView 行重用可能产生的状态偏移和错乱问题
 */
fun createCheckBoxColumnCellFactory(
    bindingsTableView: TableView<BindingUiRow>,
    isCore: Boolean
): Callback<TableColumn<BindingUiRow, Boolean>, TableCell<BindingUiRow, Boolean>> {
    return Callback { _ ->
        object : TableCell<BindingUiRow, Boolean>() {
            private val cb = CheckBox()

            init {
                alignment = Pos.CENTER
                cb.setOnAction {
                    val rowIndex = index
                    if (rowIndex >= 0 && rowIndex < bindingsTableView.items.size) {
                        val rowData = bindingsTableView.items[rowIndex]
                        if (isCore) {
                            rowData.coreProperty.set(cb.isSelected)
                        } else {
                            rowData.depProperty.set(cb.isSelected)
                        }
                    }
                }
            }

            override fun updateItem(item: Boolean?, empty: Boolean) {
                super.updateItem(item, empty)
                if (empty || item == null) {
                    graphic = null
                } else {
                    cb.isSelected = item
                    graphic = cb
                }
            }
        }
    }
}

/**
 * 将 ComboRelation 枚举翻译为前台简洁的中文出牌顺序文案
 */
fun lin.bean.usePlan.ComboRelation.toChineseDesc(): String = when (this) {
    lin.bean.usePlan.ComboRelation.SCORE_ONLY -> "仅评分"
    lin.bean.usePlan.ComboRelation.CORE_BEFORE_DEP -> "核心优先"
    lin.bean.usePlan.ComboRelation.DEP_BEFORE_CORE -> "依赖优先"
}
