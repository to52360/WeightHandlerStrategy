package lin.ui.aura_boost

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.aura_boost.SaveAuraBoostInput
import lin.repository.card_group.CardManagerEntity
import lin.ui.components.action.*
import lin.ui.components.form.*
import lin.ui.components.layout.asConfigCard
import lin.ui.components.state.EditorHeaderBar
import lin.ui.components.state.EditorPhase
import lin.ui.components.state.EditorState
import lin.ui.condition_tree.components.ConditionTreeCapabilities
import lin.ui.condition_tree.components.ConditionTreeOption
import lin.ui.condition_tree.components.selectTreeId
import lin.ui.condition_tree.components.selectedTreeId

class AuraBoostEditorPanel(
    private val onSave: (SaveAuraBoostInput) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onRefreshTreesRequested: () -> Unit
) : ScrollPane() {

    // 声明式状态标题栏：标题与徽标由 EditorState 相位驱动（不再手工 setStatusBadge；左对齐沿用原头部布局）
    private val headerBar = EditorHeaderBar<AuraBoostEntity>(isCentered = false)

    private val idValueLabel = Label("(自动生成)").apply {
        style = "-fx-font-family: monospace; -fx-font-weight: bold; -fx-text-fill: #495057;"
    }
    private val managerCombo = ComboBox<ManagerFilterItem>()

    // 声明式文本输入（T-DC-010）：名称无约束（可空）；标签宽 70 与 ID/归属行对齐
    private val baseInfoForm = DeclarativeForm(
        defaultLabelWidth = 70.0,
        specs = listOf(
            TextFieldSpec(
                id = "name",
                label = "配置名称:",
                prompt = "输入配置名称，如：莱妮莎在场-低费法术加分"
            )
        )
    )

    // 声明式通用条件树选择器（触发条件树）
    private val triggerPicker = ResourcePickerBar(
        promptText = "选择触发条件树...",
        capabilities = ConditionTreeCapabilities.defaultSet(
            managerIdProvider = { getCurrentManagerId() },
            onRefreshRequested = { onRefreshTreesRequested() }
        )
    )

    // 声明式通用条件树选择器（受益过滤条件树）
    private val targetPicker = ResourcePickerBar(
        promptText = "选择受益过滤条件树...",
        capabilities = ConditionTreeCapabilities.defaultSet(
            managerIdProvider = { getCurrentManagerId() },
            onRefreshRequested = { onRefreshTreesRequested() }
        )
    )

    // 分值与操作：score 保持 TextField 交互（Decimal 约束替代手写 toDoubleOrNull 判空，不做 Spinner 化）
    private val scoreForm = DeclarativeForm(
        specs = listOf(
            TextFieldSpec(
                id = "score",
                label = "加分费值 (score):",
                prompt = "如：1.5",
                constraints = listOf(FieldConstraint.Decimal("请输入有效的费值（如 1.2）"))
            )
        )
    )

    // 编辑器动作栏：可用/可见由 EditorState 相位声明；横排右对齐以沿用原「卡片右下角」布局
    private val actionBar = EditorActionBar(
        stateProperty = headerBar.stateProperty,
        presenter = InlineActionPresenter(alignment = Pos.CENTER_RIGHT),
        actions = listOf(
            // 原散装 btnSave 从未被禁用（含空态）⇒ 声明为「恒定可用」
            EditorAction(
                label = "保存配置",
                variant = ActionVariant.SUCCESS,
                enabledWhen = listOf(ActionCondition.Always),
                handle = { handleSave() }
            ),
            // 原散装 btnDelete 仅编辑态可用、三态恒可见（灰着不消失）⇒ 可用条件收窄相位，可见条件缺省=恒可见
            EditorAction(
                label = "删除配置",
                variant = ActionVariant.DANGER,
                enabledWhen = listOf(ActionCondition.Phases(setOf(EditorPhase.EDITING))),
                handle = { handleDelete() }
            )
        )
    )

    private val contentBox = VBox(14.0)

    init {
        isFitToWidth = true
        style = "-fx-background-color: transparent;"

        contentBox.apply {
            padding = Insets(16.0)
            children.addAll(
                headerBar,
                buildBaseInfoCard(),
                triggerPicker.asConfigCard(
                    title = "1. 触发条件树 (Condition Tree)",
                    description = "当局中全局条件满足时触发光环广播（例如：莱妮莎在场）。"
                ),
                targetPicker.asConfigCard(
                    title = "2. 受益过滤条件树 (Target Condition Tree)",
                    description = "触发后，符合此条件树过滤的候选卡牌将获得额外加分（例如：法术且 cost ≤ 2）。"
                ),
                buildScoreCard()
            )
        }

        content = contentBox
    }

    private fun buildBaseInfoCard(): VBox {
        val rows = VBox(10.0).apply {
            children.addAll(
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("ID:").apply { prefWidth = 70.0; style = "-fx-font-weight: bold;" },
                        idValueLabel
                    )
                },
                baseInfoForm,
                HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("归属方案:").apply { prefWidth = 70.0; style = "-fx-font-weight: bold;" },
                        managerCombo.apply { prefWidth = 240.0 }
                    )
                }
            )
        }
        return rows.asConfigCard(title = "基础配置")
    }

    private fun buildScoreCard(): VBox {
        val body = VBox(12.0).apply {
            children.addAll(
                scoreForm,
                Label(
                    "说明：AuraBoost 走 additive 独立加分通道，命中后费值直接叠加给受益卡牌（按费值口径配置）。规则评估树无需包含光环条件，避免双倍计分与结构膨胀。"
                ).apply {
                    isWrapText = true
                    style = "-fx-text-fill: #0d6efd; -fx-font-size: 11px;"
                },
                Separator(),
                actionBar
            )
        }
        return body.asConfigCard(title = "广播评分与操作")
    }

    private fun handleSave() {
        val triggerCid = triggerPicker.selectedTreeId
        val targetCid = targetPicker.selectedTreeId
        val nameText = baseInfoForm.getText("name").trim()
        val scoreText = scoreForm.getText("score").trim()

        // 校验问题聚合：选树判空 + 文本字段约束统一走 FormPrompt 呈现单点（一次列出全部问题）
        val problems = buildList {
            if (triggerCid == null) {
                add(FieldProblem(fieldId = "trigger", label = "触发条件树", message = "请选择触发条件树"))
            }
            if (targetCid == null) {
                add(FieldProblem(fieldId = "target", label = "受益过滤条件树", message = "请选择受益过滤条件树"))
            }
        } + baseInfoForm.validate() + scoreForm.validate()

        if (problems.isNotEmpty()) {
            FormPrompt.showProblems(problems)
            return
        }

        // Decimal 约束已保证可解析（validate 通过后不可达）
        val score = scoreText.toDoubleOrNull() ?: return

        val input = SaveAuraBoostInput(
            name = nameText.ifEmpty { null },
            conditionId = triggerCid ?: return,
            targetConditionId = targetCid ?: return,
            score = score,
            managerId = managerCombo.value?.id,
            // 新建 or 更新由状态机相位决定（不再是面板自持的 id 副本）
            existingId = (headerBar.state as? EditorState.Editing)?.entity?.id
        )

        onSave(input)
    }

    private fun handleDelete() {
        val id = (headerBar.state as? EditorState.Editing)?.entity?.id ?: return
        val alert = Alert(
            Alert.AlertType.CONFIRMATION,
            "确定要删除 AuraBoost 配置 [$id] 吗？此操作不可撤销。",
            ButtonType.YES,
            ButtonType.NO
        )
        alert.headerText = "删除确认"
        alert.showAndWait().ifPresent { bt ->
            if (bt == ButtonType.YES) {
                onDelete(id)
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

    /**
     * 按 [EditorState] 相位渲染编辑器（标题 / 徽标 / 表单内容 / 动作可用性）。
     *
     * ⚠️ 调用方（Workbench）须**仅在相位或实体变化时**调用——本方法是**过渡渲染**而非幂等重绘：
     * 同相位重复调用会覆盖用户正在输入的草稿（新建态输到一半被左侧筛选刷新清掉）。
     */
    fun render(
        state: EditorState<AuraBoostEntity>,
        managers: List<CardManagerEntity>,
        treeOptions: List<ConditionTreeOption>,
        defaultManagerId: String?
    ) {
        headerBar.state = state
        when (state) {
            is EditorState.Empty -> applyEmpty()
            is EditorState.Creating -> applyCreating(managers, treeOptions, defaultManagerId)
            is EditorState.Editing -> applyEditing(state.entity, managers, treeOptions)
        }
    }

    private fun applyEditing(
        entity: AuraBoostEntity,
        managers: List<CardManagerEntity>,
        treeOptions: List<ConditionTreeOption>
    ) {
        idValueLabel.text = entity.id
        baseInfoForm.setText("name", entity.name ?: "")
        scoreForm.setText("score", entity.score.toString())

        syncEditorContext(managers, treeOptions, entity.managerId)
        triggerPicker.selectTreeId(entity.conditionId)
        targetPicker.selectTreeId(entity.targetConditionId)
    }

    private fun applyCreating(
        managers: List<CardManagerEntity>,
        treeOptions: List<ConditionTreeOption>,
        defaultManagerId: String?
    ) {
        idValueLabel.text = "(自动生成 8 位短 ID)"
        baseInfoForm.setText("name", "")
        scoreForm.setText("score", "")

        syncEditorContext(managers, treeOptions, defaultManagerId)
        triggerPicker.clearSelection()
        targetPicker.clearSelection()
    }

    private fun applyEmpty() {
        idValueLabel.text = "-"
        baseInfoForm.setText("name", "")
        scoreForm.setText("score", "")

        triggerPicker.clearSelection()
        targetPicker.clearSelection()
    }
}
