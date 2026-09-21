package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.*
import lin.repository.card_group.PresetDetail
import lin.repository.card_group.PresetReference
import lin.repository.card_group.PresetSaveInput
import lin.ui.components.action.ActionCondition
import lin.ui.components.action.ActionDecoration
import lin.ui.components.action.ActionVariant
import lin.ui.components.action.EditorAction
import lin.ui.components.action.EditorActionBar
import lin.ui.components.action.InlineActionPresenter
import lin.ui.components.state.EditorHeaderBar
import lin.ui.components.state.EditorPhase
import lin.ui.components.state.EditorState

/** 仅编辑态成立（本面板三个「需预设已落库」动作共用）。 */
private val EDITING_ONLY_PHASE = listOf(
    ActionCondition.Phases(setOf(EditorPhase.EDITING))
)

/**
 * 策略预设画像与影响总览看板面板（T-TG-016 / T-TG-017 / T-TG-037 优化版）。
 *
 * 职责定位转变：
 * 1. 本面板作为「预设画像与全局影响面看板」，承载基础信息、规模指标、已生效用途流、禁用告警看板与引用拓扑；
 * 2. 5 列大表深度编辑迁入独立全景弹窗 [StrategyPresetConfigDialog]（880×620px 宽视窗），彻底解决右侧窄栏空间不足痛点；
 * 3. 基础信息（名称、描述）支持在面板就地快速保存；深度策略声明一键唤出弹窗全景沉浸配置。
 *
 * 编辑器状态（Empty / Creating / Editing）由 Store 单一事实源产出，面板通过 [render] 消费（过渡渲染语义）。
 */
class StrategyPresetDetailPane(
    /** 读当前工作台状态（弹窗渲染数据 + 引用拓扑来源）；**只读取，不缓存**，避免第四份状态 */
    private val presetStateProvider: () -> StrategyPresetState,
    /** 保存预设：元数据 + 可选策略声明（`declaration` 为 null = 维度保持不变） */
    private val onSavePreset: (input: PresetSaveInput) -> Unit,
    /** 物理删除预设 */
    private val onDeletePreset: (presetId: String) -> Unit,
    /** 另存为新预设（fork 派生，D-TG-017）：源预设 id + 新名称 + 新描述 */
    private val onClonePreset: (sourceId: String, name: String, description: String?) -> Unit
) : VBox(10.0) {

    // ── 头部（标题/徽标由 EditorState 相位驱动）──
    private val headerBar = EditorHeaderBar<PresetDetail>(isCentered = false)

    /**
     * 编辑器动作栏（四键）。
     *
     * ⚠️ 四键**全部恒可见**（禁用态灰着而非消失），故不声明 `visibleIn`；
     * 「配置策略 / 另存为 / 删除」都要求已落库预设 ⇒ `enabledIn` 收窄到仅编辑态
     * （新建态必须先「保存基本信息」拿到 presetId，方能挂维度项——领域约束）。
     */
    private val actionBar = EditorActionBar(
        stateProperty = headerBar.stateProperty,
        presenter = InlineActionPresenter(alignment = Pos.CENTER_LEFT),
        actions = listOf(
            EditorAction(
                label = "配置策略...",
                variant = ActionVariant.PRIMARY,
                enabledWhen = EDITING_ONLY_PHASE,
                decorations = listOf(
                    ActionDecoration.TooltipText("打开全景弹窗配置用途声明（树白名单、出牌时序、惜售门槛）与全局光环白名单")
                ),
                handle = { handleOpenConfigDialog() }
            ),
            EditorAction(
                label = "保存基本信息",
                variant = ActionVariant.SUCCESS,
                decorations = listOf(
                    ActionDecoration.TooltipText("仅保存预设名称与描述修改，不改变策略声明")
                ),
                handle = { handleSaveBasic() }
            ),
            EditorAction(
                label = "另存为新预设",
                variant = ActionVariant.SECONDARY,
                enabledWhen = EDITING_ONLY_PHASE,
                decorations = listOf(
                    ActionDecoration.TooltipText("复制当前预设的「树白名单 + 时序声明 + 惜售声明 + 光环」为一个新预设（fork）")
                ),
                handle = { handleClone() }
            ),
            EditorAction(
                label = "删除预设",
                variant = ActionVariant.DANGER,
                // 领域守卫：仍被卡组引用时禁用（引用数据在 Store 状态里 ⇒ 需工作台调 refreshActions 触发重算）
                enabledWhen = EDITING_ONLY_PHASE + ActionCondition.Guard { !isCurrentPresetReferenced() },
                decorations = listOf(
                    ActionDecoration.TooltipText("删除该预设；仍被卡组引用时不可删除（引用关系见下方「被引用卡组」看板）")
                ),
                handle = { handleDelete() }
            )
        )
    )

    // ── 元数据输入项 ──
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

    private val createdAtLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #95a5a6;"
    }

    init {
        padding = Insets(10.0)

        val metaGrid = GridPane().apply {
            hgap = 8.0
            vgap = 6.0
            add(Label("ID:"), 0, 0)
            add(txtId, 1, 0)
            add(Label("名称:"), 2, 0)
            add(txtName, 3, 0)
            add(Label("描述:"), 0, 1)
            add(txtDescription, 1, 1, 3, 1)
            add(Label("创建于:"), 0, 2)
            add(createdAtLabel, 1, 2, 3, 1)
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
                headerBar,
                metaGrid,
                actionBar,
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

        // 初始空态（首次状态发射会经 EditorStateTransition 触发正式过渡渲染）
        headerBar.state = EditorState.Empty("请在左侧选择或新建预设")
        renderEmpty()
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

    // ── 编辑器动作处理（四键由 actionBar 按条件派发，可用性判定不再散落于此）──

    private fun currentDetail(): PresetDetail? = (headerBar.state as? EditorState.Editing)?.entity

    /** 供工作台在每次状态发射后触发按钮条件重算（删除键的领域守卫依据在 Store 状态里）。 */
    fun refreshActions() = actionBar.refresh()

    /** 当前预设是否仍被卡组引用（删除键守卫依据；每次求值现读 Store 状态，不缓存快照）。 */
    private fun isCurrentPresetReferenced(): Boolean {
        val presetId = currentDetail()?.preset?.id ?: return false
        return presetStateProvider().allPresets
            .find { it.preset.id == presetId }?.referencedBy.orEmpty().isNotEmpty()
    }

    private fun handleOpenConfigDialog() {
        val detail = currentDetail() ?: return

        val dialog = StrategyPresetConfigDialog(
            presetDetail = detail,
            // 弹窗渲染数据（候选树 / 时序规则 / 用途全集 / 光环候选）取自已加载状态，避免面板再缓存一份
            state = presetStateProvider(),
            onSave = { declaration ->
                onSavePreset(
                    PresetSaveInput(
                        presetId = detail.preset.id,
                        name = txtName.text.trim().ifBlank { detail.preset.name },
                        description = txtDescription.text.trim().takeIf { it.isNotBlank() },
                        declaration = declaration
                    )
                )
            }
        )
        dialog.showAndWait()
    }

    private fun handleSaveBasic() {
        val name = validatedNameOrNull(txtName.text) ?: return

        // 仅保存基本信息：declaration 缺省 = 保留既有维度项；presetId 为 null = 新建（由状态机相位决定）
        onSavePreset(
            PresetSaveInput(
                presetId = currentDetail()?.preset?.id,
                name = name,
                description = txtDescription.text.trim().takeIf { it.isNotBlank() }
            )
        )
    }

    /** 预设名称校验（新增与派生共用）：通过返回 trim 后名称；不通过弹提示并返回 null。 */
    private fun validatedNameOrNull(raw: String): String? {
        val name = raw.trim()
        if (name.isBlank()) {
            showAlert(Alert.AlertType.WARNING, "校验失败", "预设名称不能为空")
            return null
        }
        if (name.length > 60) {
            showAlert(Alert.AlertType.WARNING, "校验失败", "预设名称过长（最多 60 字符）")
            return null
        }
        return name
    }

    private fun handleDelete() {
        val presetId = currentDetail()?.preset?.id ?: return

        // ⚠️ 引用守卫的**权威判据在 Store**（删除前预检并返回错误文案），此处只做用户确认；
        //    原实现把守卫留在 UI 是唯一防线，一旦遗漏即静默删除被引用预设。
        val confirm = Alert(Alert.AlertType.CONFIRMATION).apply {
            title = "确认删除策略预设"
            headerText = "确定要删除预设 [${txtName.text}] 吗？"
            contentText = "⚠️ 注意：UI 界面操作将直接物理删除，不落快照（不可恢复）。如需可恢复删除请使用 MCP 工具。"
        }.showAndWait()

        if (confirm.isPresent && confirm.get() == ButtonType.OK) {
            onDeletePreset(presetId)
        }
    }

    private fun handleClone() {
        val source = currentDetail() ?: return
        val sourceId = source.preset.id
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
        val name = validatedNameOrNull(input) ?: return

        onClonePreset(sourceId, name, null)
    }

    /**
     * 按 [EditorState] 相位渲染面板。
     *
     * ⚠️ **过渡渲染**：调用方（Workbench）须仅在相位或实体变化时调用（见 `EditorStateTransition`），
     * 同相位重复调用会覆盖用户正在输入的名称/描述草稿。
     *
     * @param state 工作台状态（指标看板、用途名、引用拓扑的渲染数据来源）
     * @param editorState 编辑器相位状态（标题/徽标/动作可用性的单一事实源）
     */
    fun render(state: StrategyPresetState, editorState: EditorState<PresetDetail>) {
        headerBar.state = editorState
        when (editorState) {
            is EditorState.Empty -> renderEmpty()
            is EditorState.Creating -> renderCreating(state)
            is EditorState.Editing -> renderEditing(editorState.entity, state)
        }
    }

    private fun renderEditing(detail: PresetDetail, state: StrategyPresetState) {
        val timeStr = detail.preset.createdAt?.take(19)?.replace('T', ' ')
        createdAtLabel.text = if (timeStr != null) timeStr else ""

        txtId.text = detail.preset.id
        txtName.text = detail.preset.name
        txtDescription.text = detail.preset.description ?: ""

        // 1. 规模指标卡片
        val declaredTags = detail.treeSelections.keys
        lblMetricPurposes.text = "${declaredTags.size}"
        lblMetricTrees.text = "${detail.treeSelections.values.sumOf { it.size }}"
        lblMetricTimings.text = "${detail.timings.size}"
        lblMetricAuras.text = "${detail.auraSelection.size}"

        // 2. 已声明用途卡片流
        renderDeclaredFlow(detail, state.tagDisplayNames)

        // 3. 被禁用用途看板（口径 = 树维度声明）
        disabledPurposesBoard.updatePurposes(state.purposeUniverse, declaredTags, state.tagDisplayNames)

        // 4. 引用关系
        renderReferences(state.allPresets.find { it.preset.id == detail.preset.id }?.referencedBy.orEmpty())
    }

    private fun renderCreating(state: StrategyPresetState) {
        createdAtLabel.text = ""
        txtId.text = "(系统自动生成)"
        txtName.text = ""
        txtDescription.text = ""

        lblMetricPurposes.text = "0"
        lblMetricTrees.text = "0"
        lblMetricTimings.text = "0"
        lblMetricAuras.text = "0"

        declaredFlow.children.setAll(
            Label("新建预设尚未保存。请先在上方输入名称并点击「保存基本信息」。").apply {
                style = "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;"
            }
        )

        disabledPurposesBoard.updatePurposes(state.purposeUniverse, emptySet(), state.tagDisplayNames)
        referenceListLabel.text = "新建预设尚未被任何卡组引用"
        referenceListLabel.style = "-fx-text-fill: #7f8c8d;"
    }

    private fun renderEmpty() {
        createdAtLabel.text = ""
        txtId.text = ""
        txtName.text = ""
        txtDescription.text = ""

        lblMetricPurposes.text = "-"
        lblMetricTrees.text = "-"
        lblMetricTimings.text = "-"
        lblMetricAuras.text = "-"

        declaredFlow.children.clear()
        disabledPurposesBoard.updatePurposes(emptySet(), emptySet(), emptyMap())
        referenceListLabel.text = "无"
        referenceListLabel.style = "-fx-text-fill: #7f8c8d;"
    }

    private fun renderReferences(refs: List<PresetReference>) {
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

