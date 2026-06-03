package lin.utils

import javafx.beans.property.SimpleStringProperty
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView

/**
 * 通用高性能 JavaFX TableView 列简易声明扩展函数
 * 支持链式/流畅式声明，彻底消除 TableColumn, setCellValueFactory, SimpleStringProperty 等冗长样板代码
 */
fun <S> TableView<S>.addColumn(
    title: String,
    width: Double? = null,
    isCentered: Boolean = false,
    valueProvider: (S) -> String
): TableColumn<S, String> {
    val column = TableColumn<S, String>(title).apply {
        setCellValueFactory { SimpleStringProperty(valueProvider(it.value)) }
        if (width != null) {
            prefWidth = width
        }
        if (isCentered) {
            style = "-fx-alignment: CENTER;"
        }
    }
    this.columns.add(column)
    return column
}
