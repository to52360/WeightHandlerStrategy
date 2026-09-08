package lin.ui.aura_boost

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInput
import lin.repository.card_group.CardManagerEntity
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.ui.card_group.behavior.ConditionTreeOption
import lin.ui.condition_tree.ConditionTreeDialog
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AuraBoostEditorPanel : ScrollPane(), KoinComponent {

    var onSave: ((SaveAuraBoostInput) -> Unit)? = null
    var onDelete: ((String) -> Unit)? = null
    var onRefreshTreesRequested: (() -> Unit)? = null

    private val conditionTreeRepository: ConditionTreeConfigRepository by inject()

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

    // 触发条件树
    private val triggerTreeCombo = ComboBox<ConditionTreeOption>().apply {
        promptText = "选择触发条件树..."
    }
    private val btnTriggerNew = Button("➕ 新建").apply {
        style = "-fx-background-color: #198754; -fx-text-fill: white; -fx-font-size: 11px;"
    }
    private val btnTriggerEdit = Button("✏️ 编辑").apply {
        style = "-fx-background-color: #0d6efd; -fx-text-fill: white; -fx-font-size: 11px;"
    }
    private val btnTriggerPreview = Button("👁 预览").apply {
        style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-size: 11px;"
    }

    // 受益过滤条件树
    private val targetTreeCombo = ComboBox<ConditionTreeOption>().apply {
        promptText = "选择受益过滤条件树..."
    }
    private val btnTargetNew = Button("➕ 新建").apply {
        style = "-fx-background-color: #198754; -fx-text-fill: white; -fx-font-size: 11px;"
    }
    private val btnTargetEdit = Button("✏️ 编辑").apply {
        style = "-fx-background-color: #0d6efd; -fx-text-fill: white; -fx-font-size: 11px;"
    }
    private val btnTargetPreview = Button("👁 预览").apply {
        style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-size: 11px;"
    }

    // 分值与操作
    private val scoreField = TextField().apply {
        promptText = "如：1.5"
        prefWidth = 120.0
    }

    private val btnSave = Button("💾 保存配置").apply {
        style = "-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 6 16;"
    }
    private val btnDelete = Button("🗑️ 删除配置").apply {
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
                buildTreeCard(
                    cardTitle = "1. 触发条件树 (Condition Tree)",
                    cardDesc = "当局中全局条件满足时触发光环广播（例如：莱妮莎在场）。",
                    combo = triggerTreeCombo,
                    btnNew = btnTriggerNew,
                    btnEdit = btnTriggerEdit,
                    btnPreview = btnTriggerPreview
                ),
                buildTreeCard(
                    cardTitle = "2. 受益过滤条件树 (Target Condition Tree)",
                    cardDesc = "触发后，符合此条件树过滤的候选卡牌将获得额外加分（例如：法术且 cost ≤ 2）。",
                    combo = targetTreeCombo,
                    btnNew = btnTargetNew,
                    btnEdit = btnTargetEdit,
                    btnPreview = btnTargetPreview
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
                Label("📌 基础配置").apply {
                    style = "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #495057;"
                },
                row1, row2, row3
            )
        }
        return grid
    }

    private fun buildTreeCard(
        cardTitle: String,
        cardDesc: String,
        combo: ComboBox<ConditionTreeOption>,
        btnNew: Button,
        btnEdit: Button,
        btnPreview: Button
    ): VBox {
        return VBox(8.0).apply {
            padding = Insets(12.0)
            style =
                "-fx-background-color: #ffffff; -fx-border-color: #dee2e6; -fx-border-radius: 6; -fx-background-radius: 6;"

            val titleLabel = Label(cardTitle).apply {
                style = "-fx-font-weight: bold; -fx-font-size: 13px; -fx-text-fill: #495057;"
            }
            val descLabel = Label(cardDesc).apply {
                style = "-fx-text-fill: #6c757d; -fx-font-size: 11px;"
            }

            listOf(btnNew, btnEdit, btnPreview).forEach { btn ->
                btn.minWidth = Region.USE_PREF_SIZE
            }
            HBox.setHgrow(combo, Priority.ALWAYS)
            combo.maxWidth = Double.MAX_VALUE

            val actionRow = HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(
                    Label("条件树:"),
                    combo,
                    btnNew,
                    btnEdit,
                    btnPreview
                )
            }

            children.addAll(titleLabel, descLabel, actionRow)
        }
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
                "💡 说明：AuraBoost 走 additive 独立加分通道，命中后费值直接叠加给受益卡牌（Q-024 后按费配）。规则评估树无需包含光环条件，避免双倍计分与结构膨胀。"
            ).apply {
                isWrapText = true
                style = "-fx-text-fill: #0d6efd; -fx-font-size: 11px;"
            }

            val btnRow = HBox(12.0).apply {
                alignment = Pos.CENTER_RIGHT
                children.addAll(btnDelete, btnSave)
            }

            children.addAll(
                Label("⚡ 广播评分与操作").apply {
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
        bindTreeActions(
            combo = triggerTreeCombo,
            btnNew = btnTriggerNew,
            btnEdit = btnTriggerEdit,
            btnPreview = btnTriggerPreview,
            emptySelectionWarning = "请先选择一个触发条件树。"
        )
        bindTreeActions(
            combo = targetTreeCombo,
            btnNew = btnTargetNew,
            btnEdit = btnTargetEdit,
            btnPreview = btnTargetPreview,
            emptySelectionWarning = "请先选择一个受益过滤条件树。"
        )

        btnSave.setOnAction { handleSave() }
        btnDelete.setOnAction { handleDelete() }
    }

    private fun bindTreeActions(
        combo: ComboBox<ConditionTreeOption>,
        btnNew: Button,
        btnEdit: Button,
        btnPreview: Button,
        emptySelectionWarning: String
    ) {
        btnNew.setOnAction {
            val dialog = ConditionTreeDialog(autoCreateDraft = true, managerId = getCurrentManagerId())
            dialog.showAndWait().ifPresent { createdId ->
                onRefreshTreesRequested?.invoke()
                selectTreeInCombo(combo, createdId)
            }
        }
        btnEdit.setOnAction {
            val selectedId = combo.value?.id
            if (selectedId.isNullOrEmpty()) {
                Alert(Alert.AlertType.WARNING, emptySelectionWarning).showAndWait()
                return@setOnAction
            }
            val dialog = ConditionTreeDialog(initialSelectTreeId = selectedId, managerId = getCurrentManagerId())
            dialog.showAndWait().ifPresent { editedId ->
                onRefreshTreesRequested?.invoke()
                selectTreeInCombo(combo, editedId)
            }
        }
        btnPreview.setOnAction {
            previewTree(combo.value?.id)
        }
    }

    private fun handleSave() {
        val name = nameField.text.trim().ifEmpty { null }
        val triggerCid = triggerTreeCombo.value?.id?.takeIf { it.isNotEmpty() }
        val targetCid = targetTreeCombo.value?.id?.takeIf { it.isNotEmpty() }
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

    private fun previewTree(treeId: String?) {
        if (treeId.isNullOrEmpty()) {
            Alert(Alert.AlertType.INFORMATION, "当前未选择条件树。").showAndWait()
            return
        }
        val entity = conditionTreeRepository.findById(treeId)
        if (entity == null) {
            Alert(Alert.AlertType.ERROR, "未在数据库中找到 ID 为 [$treeId] 的条件树配置。").showAndWait()
        } else {
            val content =
                "【条件树 ID】: ${entity.id}\n【条件树名称】: ${entity.name}\n【关联卡组 ID】: ${entity.managerId ?: "(全局共享)"}\n\n【节点配置 JSON 摘要】:\n${entity.configData}"
            Alert(Alert.AlertType.INFORMATION, content).apply {
                title = "条件树预览"
                headerText = "条件树配置摘要详情"
            }.showAndWait()
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
        val prevTriggerId = triggerTreeCombo.value?.id
        val prevTargetId = targetTreeCombo.value?.id

        triggerTreeCombo.items.setAll(options)
        targetTreeCombo.items.setAll(options)

        selectTreeInCombo(triggerTreeCombo, prevTriggerId)
        selectTreeInCombo(targetTreeCombo, prevTargetId)
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
        selectTreeInCombo(triggerTreeCombo, entity.conditionId)
        selectTreeInCombo(targetTreeCombo, entity.targetConditionId)
    }

    fun enterCreatingMode(
        managers: List<CardManagerEntity>,
        defaultManagerIdSnapshot: String?,
        treeOptions: List<ConditionTreeOption>
    ) {
        currentEntityId = null
        setStatusBadge("➕ 新建模式", "#198754")
        idValueLabel.text = "(自动生成 8 位短 ID)"

        nameField.clear()
        scoreField.clear()
        btnDelete.isDisable = true

        syncEditorContext(managers, treeOptions, defaultManagerIdSnapshot)
        triggerTreeCombo.value = null
        targetTreeCombo.value = null
    }

    fun clearEditor() {
        currentEntityId = null
        setStatusBadge("未选择配置", "#6c757d")
        idValueLabel.text = "-"

        nameField.clear()
        scoreField.clear()
        btnDelete.isDisable = true

        triggerTreeCombo.value = null
        targetTreeCombo.value = null
    }

    private fun setStatusBadge(text: String, bgColor: String) {
        statusBadge.text = text
        statusBadge.style =
            "-fx-background-color: $bgColor; -fx-text-fill: white; -fx-padding: 3 8; -fx-background-radius: 10; -fx-font-size: 11px;"
    }

    private fun selectTreeInCombo(combo: ComboBox<ConditionTreeOption>, id: String?) {
        if (id.isNullOrEmpty()) {
            combo.value = null
            return
        }
        val matched = combo.items.find { it.id == id }
        if (matched != null) {
            combo.value = matched
        } else {
            val temp = ConditionTreeOption(id, "自定义/其他条件树 ($id)")
            combo.items.add(temp)
            combo.value = temp
        }
    }
}
