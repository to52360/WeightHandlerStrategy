package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_group.TimingOverride

/**
 * 策略预设详情面板（T-TG-016 / T-TG-017）。
 *
 * 整合预设元数据编辑、全局树正向白名单选择、用途出牌时序覆盖、
 * 被禁用用途看板（口径单点化）与防误删前置阻断拦截。
 */
class StrategyPresetDetailPane(
    private val service: StrategyPresetService
) : VBox(10.0) {

    /** 保存预设（含元数据 + 树白名单 + 时序覆盖，维度级整体替换） */
    var onSavePreset: ((
        presetId: String?,
        name: String,
        description: String?,
        treeSelections: Map<String, Collection<String>>?,
        timings: Map<String, TimingOverride>?
    ) -> Unit)? = null

    /** 物理删除预设 */
    var onDeletePreset: ((presetId: String) -> Unit)? = null

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

    // 三大功能子组件（T-TG-017）
    private val treeSelectionPanel = TreeSelectionPanel()
    private val timingOverridePanel = TimingOverridePanel()
    private val disabledPurposesBoard = DisabledPurposesBoard()

    // Tab 导航组织
    private val tabPane = TabPane()
    private val tabTrees = Tab("🌲 树正向白名单", treeSelectionPanel).apply { isClosable = false }
    private val tabTimings = Tab("⏱️ 用途时序覆盖", timingOverridePanel).apply { isClosable = false }
    private val tabDisabled = Tab("🚫 被禁用用途看板", disabledPurposesBoard).apply { isClosable = false }

    // 引用卡组展示区
    private val referenceSection = VBox(4.0).apply {
        style = "-fx-border-color: #bdc3c7; -fx-border-width: 1px; -fx-border-radius: 4px; -fx-padding: 6px;"
    }
    private val referenceListLabel = Label()

    private var currentPresetId: String? = null
    private var currentUniverse: Set<String> = emptySet()
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
            children.addAll(btnSave, btnDelete)
        }

        referenceSection.children.addAll(
            Label("🔗 被引用卡组：").apply { style = "-fx-font-weight: bold; -fx-font-size: 11px;" },
            referenceListLabel
        )

        tabPane.tabs.addAll(tabTrees, tabTimings, tabDisabled)
        VBox.setVgrow(tabPane, Priority.ALWAYS)

        children.addAll(headerBox, metaGrid, buttonBox, tabPane, referenceSection)

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

        // 树选择变化时，动态联动更新被禁用用途看板
        treeSelectionPanel.onSelectionsChanged = { declaredTags ->
            disabledPurposesBoard.updatePurposes(currentUniverse, declaredTags)
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

        // Q5 三态语义防呆：门槛「设值」模式数值无效时必须拦截（否则会被静默解释为清除门槛）
        timingOverridePanel.validate()?.let {
            showAlert(Alert.AlertType.WARNING, "时序覆盖校验失败", it)
            return
        }

        val treeSelections = treeSelectionPanel.collectTreeSelections()
        val timings = timingOverridePanel.collectTimings()

        // Q8 防呆二次确认：空预设（0 项树声明）
        if (treeSelections.isEmpty()) {
            val confirmEmpty = Alert(Alert.AlertType.CONFIRMATION).apply {
                title = "空预设保存二次确认"
                headerText = "当前预设未勾选声明任何全局用途树（0 项树选择）"
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
            timings
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

    /** 同步展示当前预设详情或新建状态 */
    fun updateState(state: StrategyPresetState) {
        currentUniverse = state.purposeUniverse

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

        // 加载两大维度数据与刷新看板
        treeSelectionPanel.loadTrees(state.candidateTrees, detail.treeSelections)
        timingOverridePanel.loadTimings(state.timingRules, detail.timings)
        disabledPurposesBoard.updatePurposes(state.purposeUniverse, detail.treeSelections.keys)
    }

    private fun enterCreatingMode(state: StrategyPresetState) {
        isCreatingMode = true
        currentPresetId = null
        headerLabel.text = "➕ 新建策略预设"
        createdAtLabel.text = ""
        txtId.text = "(系统自动生成)"
        txtName.text = ""
        txtDescription.text = ""
        btnDelete.isDisable = true
        referenceListLabel.text = "新建预设尚未被任何卡组引用"
        referenceListLabel.style = "-fx-text-fill: #7f8c8d;"

        // 新建模式初始化空选择
        treeSelectionPanel.loadTrees(state.candidateTrees, emptyMap())
        timingOverridePanel.loadTimings(state.timingRules, emptyMap())
        disabledPurposesBoard.updatePurposes(state.purposeUniverse, emptySet())
    }

    private fun clearForm() {
        isCreatingMode = false
        currentPresetId = null
        headerLabel.text = "请在左侧选择或新建预设"
        createdAtLabel.text = ""
        txtId.text = ""
        txtName.text = ""
        txtDescription.text = ""
        btnDelete.isDisable = true
        referenceListLabel.text = "无"
        referenceListLabel.style = "-fx-text-fill: #7f8c8d;"

        treeSelectionPanel.loadTrees(emptyList(), emptyMap())
        timingOverridePanel.loadTimings(emptyList(), emptyMap())
        disabledPurposesBoard.updatePurposes(emptySet(), emptySet())
    }

    private fun showAlert(type: Alert.AlertType, title: String, content: String) {
        Alert(type).apply {
            this.title = title
            this.headerText = null
            this.contentText = content
        }.showAndWait()
    }
}
