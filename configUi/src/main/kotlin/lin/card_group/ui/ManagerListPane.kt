package lin.card_group.ui

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.dao.CardGroupJsonParser

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
            val btnNew = Button("新建").apply { setOnAction { showCreateDialog() } }
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

        setVgrow(managerListView, javafx.scene.layout.Priority.ALWAYS)
        children.addAll(toolBar, managerListView)
    }

    /** 弹出新建 Manager 对话框 */
    private fun showCreateDialog() {
        val dialog = Dialog<Pair<String, String>>().apply {
            title = "新建分组方案"
            headerText = "请选择对应的 .cardgroup 来源文件并设置方案名称"

            val fileNames = CardGroupJsonParser.listAvailableFiles()
            val fileCombo = ComboBox<String>().apply {
                items = FXCollections.observableArrayList(fileNames)
                promptText = "选择 .cardgroup 文件"
                maxWidth = Double.MAX_VALUE
            }

            val nameField = TextField().apply {
                promptText = "方案名称 (留空则默认为文件名)"
            }

            dialogPane.content = GridPane().apply {
                hgap = 10.0
                vgap = 10.0
                padding = Insets(20.0, 150.0, 10.0, 10.0)

                add(Label("来源文件:"), 0, 0)
                add(fileCombo, 1, 0)
                add(Label("方案名称:"), 0, 1)
                add(nameField, 1, 1)
            }

            val okButtonType = ButtonType("创建", ButtonBar.ButtonData.OK_DONE)
            dialogPane.buttonTypes.addAll(okButtonType, ButtonType.CANCEL)

            // 结果转换
            setResultConverter { buttonType ->
                if (buttonType == okButtonType) {
                    val file = fileCombo.value ?: ""
                    val name = nameField.text.ifBlank { file }
                    if (file.isNotBlank()) file to name else null
                } else null
            }
        }

        dialog.showAndWait().ifPresent { (file, name) ->
            store.createNewManager(file, name)
        }
    }
}
