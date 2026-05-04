package lin.card_group.ui

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ListView
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/**
 * 左侧：Manager 列表面板
 */
class ManagerListPane(private val store: WorkbenchStore) : VBox(10.0) {

    private val managerListView = ListView<CardManagerItem>()
    private val observableItems = FXCollections.observableArrayList<CardManagerItem>()

    init {
        padding = Insets(10.0)

        // 顶部操作栏
        val toolBar = HBox(5.0).apply {
            val btnNew = Button("新建").apply { setOnAction { store.createNewManager() } }
            val btnSave = Button("保存").apply { setOnAction { store.saveCurrentManager() } }
            val btnDelete = Button("删除").apply { setOnAction { store.deleteCurrentManager() } }
            children.addAll(btnNew, btnSave, btnDelete)
        }

        // 绑定数据源
        managerListView.items = observableItems

        // UI 操作 -> 触发 Store 行为
        managerListView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            // 防抖：防止重复设置
            if (store.state.selectedManagerItem != newValue) {
                store.selectManager(newValue)
            }
        }

        // Store 状态 -> 驱动 UI 渲染
        store.stateProperty.addListener { _, oldState, newState ->
            if (oldState.managers != newState.managers) {
                observableItems.setAll(newState.managers)
            }
            if (managerListView.selectionModel.selectedItem != newState.selectedManagerItem) {
                managerListView.selectionModel.select(newState.selectedManagerItem)
            }
        }

        VBox.setVgrow(managerListView, javafx.scene.layout.Priority.ALWAYS)
        children.addAll(toolBar, managerListView)
    }
}
