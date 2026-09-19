package lin.ui.aura_boost

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInput
import lin.repository.card_group.CardManagerEntity
import lin.ui.components.action.ResourcePickerBar
import lin.ui.components.layout.asConfigCard
import lin.ui.condition_tree.components.ConditionTreeCapabilities
import lin.ui.condition_tree.components.ConditionTreeOption
import lin.ui.condition_tree.components.selectTreeId
import lin.ui.condition_tree.components.selectedTreeId

class AuraBoostEditorPanel : ScrollPane() {

    var onSave: ((SaveAuraBoostInput) -> Unit)? = null
    var onDelete: ((String) -> Unit)? = null
    var onRefreshTreesRequested: (() -> Unit)? = null

    // 状态保持
    private var currentEntityId: String? = null

    // UI Controls
    private val titleLabel = Label("AuraBoost 配置详情").apply {
        style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;"
    }
    private val statusBadge = Label("请选择或新建配置").apply {
        style =
            "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-padding: 3 8; -fx-background-radius: 10; -fx-font-size: 11px;"
    }

    private val idValueLabel = Label("(自动生成)").apply {
        style = "-fx-font-family: monospace; -fx-font-weight: bold; -fx-text-fill: #495057;"
    }
    private val nameField = TextField().apply {
        promptText = "输入配置名称，如：莱妮莎在场-低费法术加分"
    }
    private val managerCombo = ComboBox<ManagerFilterItem>()

    // 声明式通用条件树选择器（触发条件树）
    private val triggerPicker = ResourcePickerBar(
        promptText = "选择触发条件树...",
        capabilities = ConditionTreeCapabilities.defaultSet(
            managerIdProvider = { getCurrentManagerId() },
            onRefreshRequested = { onRefreshTreesRequested?.invoke() }
        )
    )

    // 声明式通用条件树选择器（受益过滤条件树）
    private val targetPicker = ResourcePickerBar(
        promptText = "选择受益过滤条件树...",
        capabilities = ConditionTreeCapabilities.defaultSet(
            managerIdProvider = { getCurrentManagerId() },
            onRefreshRequested = { onRefreshTreesRequested?.invoke() }
        )
    )

    // 分值与操作
    private val scoreField = TextField().apply {
        promptText = "如：1.5"
        prefWidth = 120.0
    }

    private val btnSave = Button("保存配置").apply {
        style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6 16;"
    }
    private val btnDelete = Button("删除配置").apply {
        style = "-fx-background-color: #dc3545; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6 16;"
        isDisable = true
    }

    private val contentBox = VBox(14.0)

    init {
        isFitToWidth = true
        style = "-fx-background-color: transparent;"

        contentBox.apply {
            padding = Insets(16.0)
            children.addAll(
                buildHeaderBlock(),
                buildBaseInfoCard(),
                triggerPicker.asConfigCard(
                    title = "1. 触发条件树 (Condition Tree)",
                    description = "当局中全局条件满足时触发光环广播（例如：莱妮莎在场）。"
                ),
                targetPicker.asConfigCard(
                    title = "2. 受益过滤条件树 (Target Condition Tree)",
                    description = "触发后，符合此条件树过滤的候选卡牌将获得额外加分（例如：法术且 cost ≤ 2）。"
                ),
                buildScoreAndActionCard()
            )
        }

        content = contentBox

        setupButtonActions()
    }

    private fun buildHeaderBlock(): HBox {
        return HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(titleLabel, statusBadge)
        }
    }

    private fun buildBaseInfoCard(): VBox {
        val grid = VBox(10.0).apply {
            padding = Insets(12.0)
            style =
                "-fx-background-color: #ffffff; -fx-border-color: #dee2e6; -fx-border-radius: 6; -fx-background-radius: 6;"

            val row1 = HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label("ID:").apply { prefWidth = 70.0; style = "-fx-font-weight: bold;" },
                    idValueLabel
                )
            }

            val row2 = HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label("配置名称:").apply { prefWidth = 70.0; style = "-fx-font-weight: bold;" },
                    nameField.apply { HBox.setHgrow(this, Priority.ALWAYS) }
                )
            }

            val row3 = HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label("归属方案:").apply { prefWidth = 70.0; style = "-fx-font-weight: bold;" },
                    managerCombo.apply { prefWidth = 240.0 }
                )
            }

            children.addAll(
                Label("基础配置").apply {
                    style = "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #495057;"
                },
                row1, row2, row3
            )
        }
        return grid
    }

    private fun buildScoreAndActionCard(): VBox {
        return VBox(12.0).apply {
            padding = Insets(12.0)
            style =
                "-fx-background-color: #ffffff; -fx-border-color: #dee2e6; -fx-border-radius: 6; -fx-background-radius: 6;"

            val scoreRow = HBox(10.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label("加分费值 (score):").apply { style = "-fx-font-weight: bold;" },
                    scoreField,
                    Label("费").apply { style = "-fx-text-fill: #6c757d;" }
                )
            }

            val tipLabel = Label(
                "说明：AuraBoost 走 additive 独立加分通道，命中后费值直接叠加给受益卡牌（按费值口径配置）。规则评估树无需包含光环条件，避免双倍计分与结构膨胀。"
            ).apply {
                isWrapText = true
                style = "-fx-text-fill: #0d6efd; -fx-font-size: 11px;"
            }

            val btnRow = HBox(12.0).apply {
                alignment = Pos.CENTER_RIGHT
                children.addAll(btnDelete, btnSave)
            }

            children.addAll(
                Label("广播评分与操作").apply {
                    style = "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #495057;"
                },
                scoreRow,
                tipLabel,
                Separator(),
                btnRow
            )
        }
    }

    private fun setupButtonActions() {
        btnSave.setOnAction { handleSave() }
        btnDelete.setOnAction { handleDelete() }
    }

    private fun handleSave() {
        val name = nameField.text.trim().ifEmpty { null }
        val triggerCid = triggerPicker.selectedTreeId
        val targetCid = targetPicker.selectedTreeId
        val score = scoreField.text.trim().toDoubleOrNull()
        val managerId = managerCombo.value?.id

        if (triggerCid == null) {
            Alert(Alert.AlertType.ERROR, "请选择触发条件树。").showAndWait()
            return
        }
        if (targetCid == null) {
            Alert(Alert.AlertType.ERROR, "请选择受益过滤条件树。").showAndWait()
            return
        }
        if (score == null) {
            Alert(Alert.AlertType.ERROR, "请输入有效的费值（如 1.2）。").showAndWait()
            return
        }

        val input = SaveAuraBoostInput(
            name = name,
            conditionId = triggerCid,
            targetConditionId = targetCid,
            score = score,
            managerId = managerId,
            existingId = currentEntityId
        )

        onSave?.invoke(input)
    }

    private fun handleDelete() {
        val id = currentEntityId ?: return
        val alert = Alert(
            Alert.AlertType.CONFIRMATION,
            "确定要删除 AuraBoost 配置 [$id] 吗？此操作不可撤销。",
            ButtonType.YES,
            ButtonType.NO
        )
        alert.headerText = "删除确认"
        alert.showAndWait().ifPresent { bt ->
            if (bt == ButtonType.YES) {
                onDelete?.invoke(id)
            }
        }
    }

    private fun getCurrentManagerId(): String? = managerCombo.value?.id

    fun syncManagers(managers: List<CardManagerEntity>) {
        val items = buildList {
            add(ManagerFilterItem(null, "(全局共享)"))
            addAll(managers.map { ManagerFilterItem(it.id, it.name) })
        }
        val currentSelectedId = managerCombo.value?.id
        managerCombo.items.setAll(items)
        val matched = items.find { it.id == currentSelectedId } ?: items.first()
        managerCombo.value = matched
    }

    fun syncConditionTrees(options: List<ConditionTreeOption>) {
        val prevTriggerId = triggerPicker.selectedTreeId
        val prevTargetId = targetPicker.selectedTreeId

        triggerPicker.setItems(options, retainSelection = false)
        targetPicker.setItems(options, retainSelection = false)

        triggerPicker.selectTreeId(prevTriggerId)
        targetPicker.selectTreeId(prevTargetId)
    }

    private fun syncEditorContext(
        managers: List<CardManagerEntity>,
        treeOptions: List<ConditionTreeOption>,
        targetManagerId: String?
    ) {
        syncManagers(managers)
        val managerItem = managerCombo.items.find { it.id == targetManagerId } ?: managerCombo.items.first()
        managerCombo.value = managerItem
        syncConditionTrees(treeOptions)
    }

    fun loadEntity(
        entity: AuraBoostEntity,
        managers: List<CardManagerEntity>,
        treeOptions: List<ConditionTreeOption>
    ) {
        currentEntityId = entity.id
        setStatusBadge("编辑 ID: [${entity.id}]", "#0d6efd")
        idValueLabel.text = entity.id

        nameField.text = entity.name ?: ""
        scoreField.text = entity.score.toString()
        btnDelete.isDisable = false

        syncEditorContext(managers, treeOptions, entity.managerId)
        triggerPicker.selectTreeId(entity.conditionId)
        targetPicker.selectTreeId(entity.targetConditionId)
    }

    fun enterCreatingMode(
        managers: List<CardManagerEntity>,
        defaultManagerIdSnapshot: String?,
        treeOptions: List<ConditionTreeOption>
    ) {
        currentEntityId = null
        setStatusBadge("新建模式", "#198754")
        idValueLabel.text = "(自动生成 8 位短 ID)"

        nameField.clear()
        scoreField.clear()
        btnDelete.isDisable = true

        syncEditorContext(managers, treeOptions, defaultManagerIdSnapshot)
        triggerPicker.clearSelection()
        targetPicker.clearSelection()
    }

    fun clearEditor() {
        currentEntityId = null
        setStatusBadge("未选择配置", "#6c757d")
        idValueLabel.text = "-"

        nameField.clear()
        scoreField.clear()
        btnDelete.isDisable = true

        triggerPicker.clearSelection()
        targetPicker.clearSelection()
    }

    private fun setStatusBadge(text: String, bgColor: String) {
        statusBadge.text = text
        statusBadge.style =
            "-fx-background-color: $bgColor; -fx-text-fill: white; -fx-padding: 3 8; -fx-background-radius: 10; -fx-font-size: 11px;"
    }
}
