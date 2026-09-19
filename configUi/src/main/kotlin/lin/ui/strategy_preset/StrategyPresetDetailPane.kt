package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import lin.repository.card_group.PresetDetail
import lin.repository.card_group.StrategyPresetService
import lin.repository.card_group.SurplusOverride
import lin.repository.card_group.TimingOverride

/**
 * 策略预设画像与影响总览看板面板（T-TG-016 / T-TG-017 / T-TG-037 优化版）。
 *
 * 职责定位转变：
 * 1. 本面板作为「预设画像与全局影响面看板」，承载基础信息、规模指标、已生效用途流、禁用告警看板与引用拓扑；
 * 2. 5 列大表深度编辑迁入独立全景弹窗 [StrategyPresetConfigDialog]（880×620px 宽视窗），彻底解决右侧窄栏空间不足痛点；
 * 3. 基础信息（名称、描述）支持在面板就地快速保存；深度策略声明一键唤出弹窗全景沉浸配置。
 */
class StrategyPresetDetailPane(
    private val service: StrategyPresetService
) : VBox(10.0) {

    /** 保存预设（含元数据 + 树白名单 + 时序声明 + 惜售声明 + 光环白名单，各维度传 null 表示保持不变） */
    var onSavePreset: ((
        presetId: String?,
        name: String,
        description: String?,
        treeSelections: Map<String, Collection<String>>?,
        timings: Map<String, TimingOverride>?,
        surplus: Map<String, SurplusOverride>?,
        auraSelection: Set<String>?
    ) -> Unit)? = null

    /** 物理删除预设 */
    var onDeletePreset: ((presetId: String) -> Unit)? = null

    /** 另存为新预设（fork 派生，D-TG-017）：源预设 id + 新名称 + 新描述 */
    var onClonePreset: ((sourceId: String, name: String, description: String?) -> Unit)? = null

    // ── 头部与元数据 ──
    private val headerLabel = Label("策略预设详情").apply {
        style = "-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;"
    }

    private val createdAtLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #95a5a6;"
    }

    private val txtId = TextField().apply {
        isEditable = false
        style = "-fx-background-color: #ecf0f1; -fx-text-fill: #7f8c8d;"
        prefWidth = 130.0
    }

    private val txtName = TextField().apply {
        promptText = "预设名称（必填，<= 60 字符）..."
        prefWidth = 200.0
    }

    private val txtDescription = TextField().apply {
        promptText = "预设描述（可选）..."
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    // ── 操作按钮栏 ──
    private val btnOpenConfigDialog = Button("配置策略...").apply {
        style = "-fx-background-color: #2980b9; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 4 10;"
        tooltip = Tooltip("打开全景弹窗配置用途声明（树白名单、出牌时序、惜售门槛）与全局光环白名单")
    }

    private val btnSaveBasic = Button("保存基本信息").apply {
        style = "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 4 10;"
        tooltip = Tooltip("仅保存预设名称与描述修改，不改变策略声明")
    }

    private val btnClone = Button("另存为新预设").apply {
        style = "-fx-background-color: #16a085; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 4 10;"
        tooltip = Tooltip("复制当前预设的「树白名单 + 时序声明 + 惜售声明 + 光环」为一个新预设（fork）")
    }

    private val btnDelete = Button("删除预设").apply {
        style = "-fx-background-color: #c0392b; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand; -fx-padding: 4 10;"
    }

    // ── 1. 规模指标卡片区 ──
    private val lblMetricPurposes = Label("0").apply { style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #27ae60;" }
    private val lblMetricTrees = Label("0").apply { style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2980b9;" }
    private val lblMetricTimings = Label("0").apply { style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #8e44ad;" }
    private val lblMetricAuras = Label("0").apply { style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #d35400;" }

    // ── 2. 已声明生效用途流 ──
    private val declaredFlow = FlowPane(6.0, 6.0).apply {
        padding = Insets(4.0, 0.0, 4.0, 0.0)
    }
    private val declaredSection = VBox(4.0).apply {
        style = "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-padding: 8px;"
    }

    // ── 3. 被禁用用途看板（口径 = 全集 − 树维度已声明）──
    private val disabledPurposesBoard = DisabledPurposesBoard()

    // ── 4. 卡组引用展示区 ──
    private val referenceListLabel = Label().apply { isWrapText = true }
    private val referenceSection = VBox(4.0).apply {
        style = "-fx-border-color: #bdc3c7; -fx-border-width: 1px; -fx-border-radius: 4px; -fx-padding: 8px;"
    }

    // ── 状态暂存 ──
    private var currentState: StrategyPresetState? = null
    private var currentPresetDetail: PresetDetail? = null
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
            children.addAll(btnOpenConfigDialog, btnSaveBasic, btnClone, btnDelete)
        }

        // 组装 4 项微型指标卡
        val metricsBar = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                buildMetricCard("声明用途", lblMetricPurposes, "项战略用途已声明"),
                buildMetricCard("保留全局树", lblMetricTrees, "棵共享用途树输出"),
                buildMetricCard("自定义时序", lblMetricTimings, "项阶段/权重定制"),
                buildMetricCard("全局光环", lblMetricAuras, "个光环白名单生效")
            )
        }

        declaredSection.children.addAll(
            Label("✅ 当前预设已生效的战略用途：").apply {
                style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #2c3e50;"
            },
            declaredFlow
        )

        referenceSection.children.addAll(
            Label("🔗 被引用卡组：").apply { style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #2c3e50;" },
            referenceListLabel
        )

        val scrollContent = VBox(10.0).apply {
            children.addAll(
                headerBox,
                metaGrid,
                buttonBox,
                metricsBar,
                declaredSection,
                disabledPurposesBoard,
                referenceSection
            )
        }

        val scrollPane = ScrollPane(scrollContent).apply {
            isFitToWidth = true
            style = "-fx-background-color: transparent;"
        }
        VBox.setVgrow(scrollPane, Priority.ALWAYS)

        children.add(scrollPane)

        setupListeners()
        clearForm()
    }

    private fun buildMetricCard(title: String, valueLabel: Label, desc: String): VBox = VBox(2.0).apply {
        HBox.setHgrow(this, Priority.ALWAYS)
        style = "-fx-background-color: #ffffff; -fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-padding: 6 8;"
        children.addAll(
            Label(title).apply { style = "-fx-font-size: 11px; -fx-text-fill: #64748b;" },
            valueLabel,
            Label(desc).apply { style = "-fx-font-size: 10px; -fx-text-fill: #94a3b8;" }
        )
    }

    private fun setupListeners() {
        // 打开全景配置弹窗
        btnOpenConfigDialog.setOnAction {
            handleOpenConfigDialog()
        }

        // 保存基本信息（仅名称 + 描述）
        btnSaveBasic.setOnAction {
            handleSaveBasic()
        }

        // 删除预设
        btnDelete.setOnAction {
            val presetId = currentPresetDetail?.preset?.id ?: return@setOnAction
            handleDelete(presetId)
        }

        // 另存为新预设
        btnClone.setOnAction {
            val sourceId = currentPresetDetail?.preset?.id ?: return@setOnAction
            handleClone(sourceId)
        }
    }

    private fun handleOpenConfigDialog() {
        val detail = currentPresetDetail
        val state = currentState
        if (detail == null || state == null) {
            if (isCreatingMode) {
                showAlert(Alert.AlertType.INFORMATION, "提示", "新建预设请先在上方输入名称并点击「保存基本信息」，生成预设后再进入策略全景配置。")
            }
            return
        }

        val dialog = StrategyPresetConfigDialog(
            presetDetail = detail,
            state = state,
            onSave = { treeSelections, timings, surplus, auraSelection ->
                onSavePreset?.invoke(
                    detail.preset.id,
                    txtName.text.trim().ifBlank { detail.preset.name },
                    txtDescription.text.trim().takeIf { it.isNotBlank() },
                    treeSelections,
                    timings,
                    surplus,
                    auraSelection
                )
            }
        )
        dialog.showAndWait()
    }

    private fun handleSaveBasic() {
        val name = txtName.text.trim()
        if (name.isBlank()) {
            showAlert(Alert.AlertType.WARNING, "校验失败", "预设名称不能为空")
            return
        }
        if (name.length > 60) {
            showAlert(Alert.AlertType.WARNING, "校验失败", "预设名称过长（最多 60 字符）")
            return
        }

        val currentId = currentPresetDetail?.preset?.id
        // 仅保存基本信息时，各维度传 null 表示保留既有维度项
        onSavePreset?.invoke(
            currentId,
            name,
            txtDescription.text.trim().takeIf { it.isNotBlank() },
            null,
            null,
            null,
            null
        )
    }

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

    private fun handleClone(sourceId: String) {
        val sourceName = txtName.text.trim().ifBlank { sourceId }
        val dialog = TextInputDialog("$sourceName-副本").apply {
            title = "另存为新预设"
            headerText = "将预设 [$sourceName] 的内容复制为一个新预设"
            contentText = "• 复制内容、不建立关系：新预设与源预设此后各自独立演化，改源预设不会同步到新预设。\n" +
                    "• 源预设漏声明的用途会被一并继承（未声明 = 该用途树全禁）。\n" +
                    "• 新建后仍需在「卡组分组管理」中让卡组引用它。"
        }
        dialog.editor.promptText = "新预设名称（必填，<= 60 字符）..."

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
        currentState = state

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
        currentPresetDetail = detail
        headerLabel.text = "编辑策略预设"
        val timeStr = detail.preset.createdAt?.take(19)?.replace('T', ' ')
        createdAtLabel.text = if (timeStr != null) "创建于: $timeStr" else ""

        txtId.text = detail.preset.id
        txtName.text = detail.preset.name
        txtDescription.text = detail.preset.description ?: ""
        btnOpenConfigDialog.isDisable = false
        btnSaveBasic.isDisable = false
        btnDelete.isDisable = false
        btnClone.isDisable = false

        // 1. 刷新规模指标卡片
        val declaredTags = detail.treeSelections.keys
        val totalTrees = detail.treeSelections.values.sumOf { it.size }
        val timingCount = detail.timings.size
        val auraCount = detail.auraSelection.size

        lblMetricPurposes.text = "${declaredTags.size}"
        lblMetricTrees.text = "$totalTrees"
        lblMetricTimings.text = "$timingCount"
        lblMetricAuras.text = "$auraCount"

        // 2. 刷新已声明用途卡片流
        renderDeclaredFlow(detail, state.tagDisplayNames)

        // 3. 刷新被禁用用途看板（口径 = 树维度声明）
        disabledPurposesBoard.updatePurposes(
            state.purposeUniverse,
            declaredTags,
            state.tagDisplayNames
        )

        // 4. 刷新引用关系
        val summary = state.allPresets.find { it.preset.id == detail.preset.id }
        val refs = summary?.referencedBy.orEmpty()
        if (refs.isEmpty()) {
            referenceListLabel.text = "🌱 当前未被任何卡组引用（空闲资产，可安全重构或删除）"
            referenceListLabel.style = "-fx-text-fill: #7f8c8d;"
        } else {
            referenceListLabel.text = refs.joinToString("\n") { "• ${it.managerName} (${it.managerId})" }
            referenceListLabel.style = "-fx-text-fill: #27ae60; -fx-font-weight: bold;"
        }
    }

    private fun renderDeclaredFlow(detail: PresetDetail, tagDisplayNames: Map<String, String>) {
        declaredFlow.children.clear()

        val allDeclaredTags = (detail.treeSelections.keys + detail.timings.keys + detail.surplus.keys).toSet()

        if (allDeclaredTags.isEmpty()) {
            val emptyHint = Label("当前尚未声明任何战略用途（全部全局用途树均关闭兜底）。点击上方「配置策略」开始配置。").apply {
                style = "-fx-text-fill: #e74c3c; -fx-font-size: 11px;"
            }
            declaredFlow.children.add(emptyHint)
            return
        }

        for (tag in allDeclaredTags.sorted()) {
            val displayName = tagDisplayNames[tag] ?: tag
            val treeCount = detail.treeSelections[tag]?.size ?: 0
            val hasTiming = detail.timings.containsKey(tag)
            val hasSurplus = detail.surplus.containsKey(tag)

            val badgeText = buildString {
                append(displayName)
                append(" (")
                val parts = mutableListOf<String>()
                if (detail.treeSelections.containsKey(tag)) parts.add("${treeCount}树")
                if (hasTiming) parts.add("时序")
                if (hasSurplus) parts.add("惜售")
                append(parts.joinToString("/"))
                append(")")
            }

            val badge = Button(badgeText).apply {
                style = "-fx-background-color: #e8f8f5; -fx-text-fill: #16a085; -fx-font-weight: bold; -fx-font-size: 11px; -fx-border-color: #a3e4d7; -fx-border-radius: 3px; -fx-cursor: hand;"
                tooltip = Tooltip("用途ID: $tag\n点击打开全景弹窗配置")
                setOnAction { handleOpenConfigDialog() }
            }
            declaredFlow.children.add(badge)
        }
    }

    private fun enterCreatingMode(state: StrategyPresetState) {
        isCreatingMode = true
        currentPresetDetail = null
        headerLabel.text = "➕ 新建策略预设"
        createdAtLabel.text = ""
        txtId.text = "(系统自动生成)"
        txtName.text = ""
        txtDescription.text = ""
        btnOpenConfigDialog.isDisable = true
        btnSaveBasic.isDisable = false
        btnDelete.isDisable = true
        btnClone.isDisable = true

        lblMetricPurposes.text = "0"
        lblMetricTrees.text = "0"
        lblMetricTimings.text = "0"
        lblMetricAuras.text = "0"

        declaredFlow.children.clear()
        declaredFlow.children.add(Label("新建预设尚未保存。请先在上方输入名称并点击「保存基本信息」。").apply {
            style = "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;"
        })

        disabledPurposesBoard.updatePurposes(state.purposeUniverse, emptySet(), state.tagDisplayNames)
        referenceListLabel.text = "新建预设尚未被任何卡组引用"
        referenceListLabel.style = "-fx-text-fill: #7f8c8d;"
    }

    private fun clearForm() {
        isCreatingMode = false
        currentPresetDetail = null
        headerLabel.text = "请在左侧选择或新建预设"
        createdAtLabel.text = ""
        txtId.text = ""
        txtName.text = ""
        txtDescription.text = ""
        btnOpenConfigDialog.isDisable = true
        btnSaveBasic.isDisable = true
        btnDelete.isDisable = true
        btnClone.isDisable = true

        lblMetricPurposes.text = "-"
        lblMetricTrees.text = "-"
        lblMetricTimings.text = "-"
        lblMetricAuras.text = "-"

        declaredFlow.children.clear()
        disabledPurposesBoard.updatePurposes(emptySet(), emptySet(), emptyMap())
        referenceListLabel.text = "无"
        referenceListLabel.style = "-fx-text-fill: #7f8c8d;"
    }

    /** 供工作台回显 Store 返回的失败原因 */
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

