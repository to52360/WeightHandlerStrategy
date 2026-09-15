package lin.ui.card_group

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.dao.CardGroupJsonParser

/**
 * 左侧：Manager 列表面板
 */
class ManagerListPane(private val store: WorkbenchStore) : VBox(10.0) {

    /**
     * 删除确认（T-TG-035）：级联删的**覆盖面要提前说清** —— 否则用户以为只删了卡组方案。
     *
     * UI 删除**不落快照、不可恢复**（`D-TG-016` 裁定：UI 删除是用户自主操作），
     * 需要可恢复请走 MCP `delete(resource=card_group)`（落快照 + `restore_snapshot`）。
     */
    private fun confirmDelete() {
        val alert = Alert(Alert.AlertType.CONFIRMATION).apply {
            title = "删除卡组方案"
            headerText = "确认删除该卡组方案？"
            contentText = "将一并删除：绑定条目、关联评估树、用途增量项、光环加分（AuraBoost）、" +
                    "Combo 方案、卡组私有条件树。\n\n⚠️ UI 删除不落快照、不可恢复；" +
                    "如需可恢复，请改用 MCP：delete(resource=card_group) + restore_snapshot。"
            buttonTypes.setAll(ButtonType.OK, ButtonType.CANCEL)
        }
        if (alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            store.deleteCurrentManager()
        }
    }

    private val managerListView = ListView<CardManagerItem>()
    private val observableItems = FXCollections.observableArrayList<CardManagerItem>()

    init {
        padding = Insets(10.0)

        // 顶部操作栏
        val toolBar = HBox(5.0).apply {
            val btnNew = Button("新建").apply { setOnAction { showCreateDialog() } }
            val btnSave = Button("保存").apply { setOnAction { store.saveCurrentManager() } }
            val btnDelete = Button("删除").apply { setOnAction { confirmDelete() } }
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

        setVgrow(managerListView, Priority.ALWAYS)
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
