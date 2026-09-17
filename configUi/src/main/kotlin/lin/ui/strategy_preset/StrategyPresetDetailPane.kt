package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_group.SurplusOverride
import lin.repository.card_group.TimingOverride

/**
 * 策略预设详情面板（T-TG-016 / T-TG-017；T-TG-037 改为**按用途聚合的单页声明表**）。
 *
 * 整合：预设元数据编辑 + 「用途 × 三维度声明」聚合表（[PresetPurposeTable]，细节编辑进
 * [PresetPurposeEditDialog]，不再切页签）+ 被禁用用途看板（口径单点化）+ 防误删前置阻断拦截。
 *
 * 声明语义见 D-TG-019：预设侧 = **纯声明**（无「不覆盖」，勾 = 声明并落具体值、不勾 = 未声明）；
 * 覆盖语义只归卡组增量项。
 */
class StrategyPresetDetailPane(
    private val service: StrategyPresetService
) : VBox(10.0) {

    /** 保存预设（含元数据 + 树白名单 + 时序声明 + 惜售声明，维度级整体替换） */
    var onSavePreset: ((
        presetId: String?,
        name: String,
        description: String?,
        treeSelections: Map<String, Collection<String>>?,
        timings: Map<String, TimingOverride>?,
        surplus: Map<String, SurplusOverride>?
    ) -> Unit)? = null

    /** 物理删除预设 */
    var onDeletePreset: ((presetId: String) -> Unit)? = null

    /** 另存为新预设（fork 派生，D-TG-017）：源预设 id + 新名称 + 新描述 */
    var onClonePreset: ((sourceId: String, name: String, description: String?) -> Unit)? = null

    private val headerLabel = Label("策略预设详情").apply {
        style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;"
    }

    private val createdAtLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #95a5a6;"
    }

    private val txtId = TextField().apply {
        isEditable = false
        style = "-fx-background-color: #ecf0f1; -fx-text-fill: #7f8c8d;"
        prefWidth = 140.0
    }

    private val txtName = TextField().apply {
        promptText = "预设名称（必填，<= 60 字符）..."
        prefWidth = 200.0
    }

    private val txtDescription = TextField().apply {
        promptText = "预设描述（可选）..."
        prefWidth = 320.0
    }

    private val btnSave = Button("💾 保存预设全部修改").apply {
        style = "-fx-background-color: #2980b9; -fx-text-fill: white; -fx-font-weight: bold;"
    }

    private val btnDelete = Button("🗑️ 删除预设").apply {
        style = "-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold;"
    }

    /** 另存为新预设（fork 派生，D-TG-017）。tooltip 说明 fork 语义。 */
    private val btnClone = Button("📋 另存为新预设").apply {
        style = "-fx-background-color: #16a085; -fx-text-fill: white; -fx-font-weight: bold;"
        tooltip = Tooltip(
            "复制当前预设的「树白名单 + 时序覆盖」为一个新预设（fork）。\n" +
                    "新预设与源预设此后各自独立演化 —— 改源预设不会同步到新预设。\n" +
                    "源预设漏声明的用途会被一并继承（未声明 = 该用途树全禁）。"
        )
    }

    // 功能子组件（T-TG-037 / D-TG-019：按用途聚合的声明表取代「按维度分页签」）
    private val purposeTable = PresetPurposeTable()
    private val disabledPurposesBoard = DisabledPurposesBoard()

    // 引用卡组展示区（referenceListLabel 常被模式刷新读写；外层容器仅 init 装配用，就地构建）
    private val referenceListLabel = Label()

    private var currentPresetId: String? = null
    private var currentUniverse: Set<String> = emptySet()
    private var currentTagDisplayNames: Map<String, String> = emptyMap()
    private var isCreatingMode = false

    init {
        padding = Insets(10.0)

        val headerBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(headerLabel, createdAtLabel)
        }

        val metaGrid = GridPane().apply {
            hgap = 8.0
            vgap = 6.0
            add(Label("ID:"), 0, 0)
            add(txtId, 1, 0)
            add(Label("名称:"), 2, 0)
            add(txtName, 3, 0)
            add(Label("描述:"), 0, 1)
            add(txtDescription, 1, 1, 3, 1)
        }

        val buttonBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(btnSave, btnClone, btnDelete)
        }

        val referenceSection = VBox(4.0).apply {
            style = "-fx-border-color: #bdc3c7; -fx-border-width: 1px; -fx-border-radius: 4px; -fx-padding: 6px;"
            children.addAll(
                Label("🔗 被引用卡组：").apply { style = "-fx-font-weight: bold; -fx-font-size: 11px;" },
                referenceListLabel
            )
        }

        VBox.setVgrow(purposeTable, Priority.ALWAYS)

        children.addAll(headerBox, metaGrid, buttonBox, purposeTable, disabledPurposesBoard, referenceSection)

        setupListeners()
        clearForm()
    }

    private fun setupListeners() {
        btnSave.setOnAction {
            handleSave()
        }

        btnDelete.setOnAction {
            val presetId = currentPresetId ?: return@setOnAction
            handleDelete(presetId)
        }

        btnClone.setOnAction {
            val sourceId = currentPresetId ?: return@setOnAction
            handleClone(sourceId)
        }

        // 树维度声明变化时，动态联动更新被禁用用途看板（口径 = 全局用途全集 − 树维度已声明用途）
        purposeTable.onTreeDeclarationsChanged = { declaredTags ->
            disabledPurposesBoard.updatePurposes(currentUniverse, declaredTags, currentTagDisplayNames)
        }
    }

    private fun handleSave() {
        val name = txtName.text.trim()
        if (name.isBlank()) {
            showAlert(Alert.AlertType.WARNING, "校验失败", "预设名称不能为空")
            return
        }
        if (name.length > 60) {
            showAlert(Alert.AlertType.WARNING, "校验失败", "预设名称过长（最多 60 字符）")
            return
        }

        // 数字合法性已在 [PresetPurposeEditDialog] 应用前拦截 ⇒ 此处只做收集（字段值均为具体值）
        val treeSelections = purposeTable.collectTreeSelections()
        val timings = purposeTable.collectTimings()
        val surplus = purposeTable.collectSurplus()

        // Q8 防呆二次确认：没有任何用途声明树维度 ⇒ 引用方的全局用途树全部被关（后果大）
        if (treeSelections.isEmpty()) {
            val confirmEmpty = Alert(Alert.AlertType.CONFIRMATION).apply {
                title = "空预设保存二次确认"
                headerText = "当前预设没有任何用途声明「树白名单」"
                contentText = "⚠️ 注意：保存后将关闭引用该预设的卡组的全部全局用途树兜底策略！\n" +
                        "（空预设是合法的纯私有策略表达，但后果较大）。\n\n确定要保存为空预设吗？"
            }.showAndWait()
            if (!confirmEmpty.isPresent || confirmEmpty.get() != ButtonType.OK) {
                return
            }
        }

        onSavePreset?.invoke(
            currentPresetId,
            name,
            txtDescription.text.trim().takeIf { it.isNotBlank() },
            treeSelections,
            timings,
            surplus
        )
    }

    /**
     * 删除前置阻断拦截处理（真值来源 = service.findReferences）。
     *
     * ⚠️ 铁律（Q6 / D-TG-010）：有引用时弹窗阻断并列出卡组，不提供一键清空引用的破坏性通道。
     * ⚠️ 铁律（D-TG-016）：UI 删除直接物理删除不落快照（不可恢复）。
     */
    private fun handleDelete(presetId: String) {
        val references = service.findReferences(presetId)
        if (references.isNotEmpty()) {
            val refInfo = references.joinToString("\n") { "• ${it.managerName} (id: ${it.managerId})" }
            showAlert(
                Alert.AlertType.WARNING,
                "无法删除预设",
                "该策略预设正被以下 ${references.size} 个卡组引用，已被系统阻断：\n\n$refInfo\n\n请先前往「卡组分组管理」解除这些卡组的预设引用后重试。"
            )
            return
        }

        val confirm = Alert(Alert.AlertType.CONFIRMATION).apply {
            title = "确认删除策略预设"
            headerText = "确定要删除预设 [${txtName.text}] 吗？"
            contentText = "⚠️ 注意：UI 界面操作将直接物理删除，不落快照（不可恢复）。如需可恢复删除请使用 MCP 工具。"
        }.showAndWait()

        if (confirm.isPresent && confirm.get() == ButtonType.OK) {
            onDeletePreset?.invoke(presetId)
        }
    }

    /**
     * 另存为新预设（fork 派生，D-TG-017）。
     *
     * ⚠️ fork = 复制内容、不建立关系：新预设与源预设此后各自独立演化（改源预设不传导）。
     * ⚠️ 源预设漏声明的用途会被一并继承（未声明 = 该用途树全禁）；
     * 继承结果由「🚫 被禁用用途看板」在派生后自动刷新展示，此处不重复计算口径。
     */
    private fun handleClone(sourceId: String) {
        val sourceName = txtName.text.trim().ifBlank { sourceId }
        val dialog = TextInputDialog("$sourceName-副本").apply {
            title = "另存为新预设"
            headerText = "将预设 [$sourceName] 的内容复制为一个新预设"
            contentText = "• 复制内容、不建立关系：新预设与源预设此后各自独立演化，改源预设不会同步到新预设。\n" +
                    "• 源预设漏声明的用途会被一并继承（未声明 = 该用途树全禁），可在派生后于\n" +
                    "  「🚫 被禁用用途看板」页签核对。\n" +
                    "• 新建后仍需在「卡组分组管理」中让卡组引用它。"
        }
        dialog.editor.promptText = "新预设名称（必填，<= 60 字符）..."

        // 取消（空 Optional）静默返回；点了确定但名称为空才提示校验失败
        val input = dialog.showAndWait().orElse(null) ?: return
        val name = input.trim()
        if (name.isBlank()) {
            showAlert(Alert.AlertType.WARNING, "校验失败", "预设名称不能为空")
            return
        }
        if (name.length > 60) {
            showAlert(Alert.AlertType.WARNING, "校验失败", "预设名称过长（最多 60 字符）")
            return
        }

        onClonePreset?.invoke(sourceId, name, null)
    }

    /** 同步展示当前预设详情或新建状态 */
    fun updateState(state: StrategyPresetState) {
        currentUniverse = state.purposeUniverse
        currentTagDisplayNames = state.tagDisplayNames

        if (state.isCreating) {
            enterCreatingMode(state)
            return
        }

        val detail = state.selectedDetail
        if (detail == null) {
            clearForm()
            return
        }

        isCreatingMode = false
        currentPresetId = detail.preset.id
        headerLabel.text = "编辑策略预设"
        val timeStr = detail.preset.createdAt?.take(19)?.replace('T', ' ')
        createdAtLabel.text = if (timeStr != null) "创建于: $timeStr" else ""

        txtId.text = detail.preset.id
        txtName.text = detail.preset.name
        txtDescription.text = detail.preset.description ?: ""
        btnDelete.isDisable = false
        btnClone.isDisable = false

        // 刷新引用关系
        val summary = state.allPresets.find { it.preset.id == detail.preset.id }
        val refs = summary?.referencedBy.orEmpty()
        if (refs.isEmpty()) {
            referenceListLabel.text = "当前未被任何卡组引用（空闲资产，可安全删除）"
            referenceListLabel.style = "-fx-text-fill: #7f8c8d;"
        } else {
            referenceListLabel.text = refs.joinToString("\n") { "• ${it.managerName} (${it.managerId})" }
            referenceListLabel.style = "-fx-text-fill: #27ae60; -fx-font-weight: bold;"
        }

        // 装载「用途 × 三维度声明」聚合表，并刷新被禁用用途看板（口径 = 树维度声明）
        purposeTable.load(
            rules = state.timingRules,
            candidateTrees = state.candidateTrees,
            treeSelections = detail.treeSelections,
            timings = detail.timings,
            surplus = detail.surplus,
            tagDisplayNames = state.tagDisplayNames
        )
        disabledPurposesBoard.updatePurposes(
            state.purposeUniverse,
            purposeTable.collectTreeSelections().keys,
            state.tagDisplayNames
        )
    }

    private fun enterCreatingMode(state: StrategyPresetState) {
        isCreatingMode = true
        currentPresetId = null
        currentTagDisplayNames = state.tagDisplayNames
        headerLabel.text = "➕ 新建策略预设"
        createdAtLabel.text = ""
        txtId.text = "(系统自动生成)"
        txtName.text = ""
        txtDescription.text = ""
        btnDelete.isDisable = true
        btnClone.isDisable = true
        referenceListLabel.text = "新建预设尚未被任何卡组引用"
        referenceListLabel.style = "-fx-text-fill: #7f8c8d;"

        // 新建模式初始化空声明
        purposeTable.load(
            state.timingRules,
            state.candidateTrees,
            emptyMap(),
            emptyMap(),
            emptyMap(),
            state.tagDisplayNames
        )
        disabledPurposesBoard.updatePurposes(state.purposeUniverse, emptySet(), state.tagDisplayNames)
    }

    private fun clearForm() {
        isCreatingMode = false
        currentPresetId = null
        currentTagDisplayNames = emptyMap()
        headerLabel.text = "请在左侧选择或新建预设"
        createdAtLabel.text = ""
        txtId.text = ""
        txtName.text = ""
        txtDescription.text = ""
        btnDelete.isDisable = true
        btnClone.isDisable = true
        referenceListLabel.text = "无"
        referenceListLabel.style = "-fx-text-fill: #7f8c8d;"

        purposeTable.load(emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        disabledPurposesBoard.updatePurposes(emptySet(), emptySet(), emptyMap())
    }

    /** 供工作台回显 Store 返回的失败原因（复用本面板既有弹窗工具，不另造一套）。 */
    fun showError(content: String) {
        showAlert(Alert.AlertType.WARNING, "操作失败", content)
    }

    private fun showAlert(type: Alert.AlertType, title: String, content: String) {
        Alert(type).apply {
            this.title = title
            this.headerText = null
            this.contentText = content
        }.showAndWait()
    }
}
