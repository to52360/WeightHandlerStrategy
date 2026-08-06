package lin.ui.condition_tree

import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.condition_tree.ConditionTreeConfigRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 条件树可视化配置弹窗：用于在卡组行为配置等上下文中直接新建或编辑条件树。
 * 自包含 ConditionTreeWorkbench 独立视口，支持直接编辑节点参数，点击【确定】自动写库并选入。
 */
class ConditionTreeDialog(
    private val initialSelectTreeId: String? = null,
    private val autoCreateDraft: Boolean = false,
    private val managerId: String? = null
) : Dialog<String?>(), KoinComponent {

    private val conditionTreeRepository: ConditionTreeConfigRepository by inject()

    private val isCompactMode = autoCreateDraft || !initialSelectTreeId.isNullOrEmpty()
    private val workbench = ConditionTreeWorkbench(showList = !isCompactMode).apply {
        if (managerId != null) {
            this.targetManagerId = managerId
        }
    }

    private val nameField = TextField().apply {
        promptText = "请输入条件树名称"
        prefWidth = 260.0
    }

    private var resultSavedId: String? = null

    init {
        title =
            if (autoCreateDraft) "新建条件树" else if (initialSelectTreeId != null) "编辑条件树" else "条件树配置管理"
        headerText = "可视化配置条件节点规则，编辑后点击【确定】将自动保存并填入。"

        val dialogPane = this.dialogPane
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
        dialogPane.prefWidth = 920.0
        dialogPane.prefHeight = 620.0

        val container = VBox(10.0).apply {
            padding = Insets(10.0)
            children.addAll(buildHeaderRow(), workbench)
            VBox.setVgrow(workbench, Priority.ALWAYS)
        }
        dialogPane.content = container

        setupInitialState()
        setupOkIntercept()

        setResultConverter { buttonType ->
            if (buttonType == ButtonType.OK) {
                resultSavedId ?: workbench.configListView?.selectionModel?.selectedItem?.id
            } else {
                null
            }
        }
    }

    private fun buildHeaderRow(): HBox {
        val tipLabel = Label("提示：在此直接编辑节点参数，点击【确定】将自动写数据库落地。").apply {
            style = "-fx-text-fill: #0d6efd; -fx-font-size: 12px; -fx-font-weight: bold;"
        }

        return if (isCompactMode) {
            HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label("条件树名称:").apply { style = "-fx-font-weight: bold;" },
                    nameField,
                    tipLabel
                )
            }
        } else {
            HBox(12.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(tipLabel)
            }
        }
    }

    private fun setupInitialState() {
        if (autoCreateDraft) {
            nameField.text = "新条件树"
            workbench.initDefaultRootIfEmpty()
        } else if (!initialSelectTreeId.isNullOrEmpty()) {
            val entity = conditionTreeRepository.findById(initialSelectTreeId)
            nameField.text = entity?.name ?: "已选条件树"
            workbench.loadExistingTree(initialSelectTreeId)
        } else {
            workbench.configListView?.selectionModel?.selectedItemProperty()?.addListener { _, _, selected ->
                if (selected != null) {
                    nameField.text = selected.name
                }
            }
        }
    }

    private fun setupOkIntercept() {
        val okButton = dialogPane.lookupButton(ButtonType.OK)
        okButton.addEventFilter(ActionEvent.ACTION) { event ->
            val nameToSave =
                if (isCompactMode) nameField.text.trim() else (workbench.configListView?.selectionModel?.selectedItem?.name
                    ?: nameField.text.trim())
            if (nameToSave.isBlank()) {
                Alert(Alert.AlertType.ERROR, "条件树名称不能为空！").apply {
                    headerText = "校验失败"
                }.showAndWait()
                event.consume()
                return@addEventFilter
            }

            try {
                val targetId = if (autoCreateDraft) null else initialSelectTreeId
                    ?: workbench.configListView?.selectionModel?.selectedItem?.id
                val existingIdToUse = if (targetId?.startsWith("draft_") == true) null else targetId
                val savedId = workbench.saveCurrent(
                    name = nameToSave,
                    existingId = existingIdToUse,
                    managerId = managerId
                )
                resultSavedId = savedId
            } catch (e: Exception) {
                Alert(Alert.AlertType.ERROR, "保存条件树失败：${e.message}").apply {
                    headerText = "落库异常"
                }.showAndWait()
                event.consume()
            }
        }
    }
}

