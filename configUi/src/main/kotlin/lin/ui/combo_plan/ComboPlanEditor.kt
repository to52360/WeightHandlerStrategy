package lin.ui.combo_plan

import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.layout.VBox
import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.ComboRelation
import lin.repository.combo_plan.ComboPlanProblem
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.ui.GroupDisplay
import lin.ui.WorkbenchNavigator
import lin.ui.card_group.CardGroupExtension
import lin.ui.card_group.components.CardGroupCapabilities
import lin.ui.components.action.EditorAction
import lin.ui.components.action.EditorActionBar
import lin.ui.components.action.ResourcePickerBar
import lin.ui.components.form.*
import lin.ui.components.layout.SectionTitle
import lin.ui.components.layout.applyEditorContainerStyle
import lin.ui.components.state.EditorHeaderBar
import lin.ui.components.state.EditorState
import lin.ui.components.table.RoleColumnSpec
import lin.ui.components.table.RoleSelectionTable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ComboPlanEditor(
    private val onSave: (form: ComboPlanFormSnapshot) -> Unit,
    private val onDelete: (id: String) -> Unit
) : VBox(12.0), KoinComponent {

    private val navigator: WorkbenchNavigator by inject()

    // 声明式状态标题栏（响应 EditorState，驱动变体样式与徽标展示）
    private val headerBar = EditorHeaderBar<ComboPlanDefinition>()
    private val editorBox = VBox(12.0)

    // 声明式卡组方案选择器（包含查看详情与前往编辑能力）
    private val cardGroupPicker = ResourcePickerBar(
        promptText = "选择目标卡组方案...",
        capabilities = CardGroupCapabilities.defaultSet(
            onNavigateToManager = { manager ->
                navigator.navigateTo(CardGroupExtension.TITLE, manager.cardGroupManagerId)
            }
        )
    )

    // 选中的核心组与依赖组 ID 集合
    private val selectedCoreGroupIds = mutableSetOf<String>()
    private val selectedDepGroupIds = mutableSetOf<String>()

    /** 上一次已渲染的卡组 id：勾选集合的合法性依附于它（切卡组 / 卡组消失都要重置）。 */
    private var lastManagerId: String? = null

    // 通用多角色勾选表格（彻底消除特定 BindingUiRow 胶水模型与脆弱的 index 访问反模式）
    private val bindingTable = RoleSelectionTable<CardGroupBinding>(
        nameTitle = "分组名称 (Binding Name)",
        nameWidth = 190.0,
        nameExtractor = { GroupDisplay.displayNameWithType(it) },
        roleColumns = listOf(
            RoleColumnSpec(
                roleId = "core",
                title = "核心 (Core)",
                width = 55.0,
                isSelected = { selectedCoreGroupIds.contains(it.id) },
                onToggle = { item, selected ->
                    if (selected) selectedCoreGroupIds.add(item.id) else selectedCoreGroupIds.remove(item.id)
                }
            ),
            RoleColumnSpec(
                roleId = "dep",
                title = "依赖 (Dep)",
                width = 55.0,
                isSelected = { selectedDepGroupIds.contains(it.id) },
                onToggle = { item, selected ->
                    if (selected) selectedDepGroupIds.add(item.id) else selectedDepGroupIds.remove(item.id)
                }
            )
        )
    )

    // 声明式核心参数配置表单（纯数据 Spec 驱动，彻底消除类体内平铺散落的控件与命令式取设值样板）
    private val paramForm = DeclarativeForm(
        specs = listOf(
            FormSpecs.score(id = "score", label = "组合评分加权 (费):"),
            FormSpecs.score(
                id = "changeScore",
                label = "起手组合加分 (费):",
                tooltip = "起手换牌专用：核心组与依赖组的牌在起手同时保留时，额外加此分。\n" +
                        "用于表达「A、B 单留都一般，一起留才值钱」——单卡 changeWeight 表达不了组合溢价。\n" +
                        "⚠️ 不影响出牌评分（出牌协同加分是上面的「组合评分加权」），同一个 Combo 只计一次。"
            ),
            SelectFieldSpec(
                id = "relation",
                label = "执行顺序关系:",
                options = listOf(
                    ComboRelation.SCORE_ONLY,
                    ComboRelation.CORE_BEFORE_DEP,
                    ComboRelation.DEP_BEFORE_CORE
                ),
                default = ComboRelation.SCORE_ONLY,
                display = { it.toChineseDesc() }
            ),
            SwitchGroupSpec(
                title = "高级控制行为:",
                switches = listOf(
                    SwitchFieldSpec(
                        id = "coreMutex",
                        label = "核心组同回合硬互斥 (Core Mutex)",
                        default = true,
                        tooltip = "出牌侧：同 Combo 下多个核心组的牌不能同时打出一组。\n" +
                                "纯排序配方（score=0 + 顺序关系）必须取消勾选，否则多核心组被剪枝只剩一组。\n" +
                                "⚠️ 该开关同时作用于起手换牌：取消后起手阶段也不会再阻止这些核心牌同时保留在手中。"
                    ),
                    SwitchFieldSpec(
                        id = "mustAdjacent",
                        label = "必须相邻使用 (Adjacent) —— 未实现",
                        default = false,
                        isDisabled = true,
                        tooltip = "引擎当前不支持强相邻约束：UsePlanOrderer 不消费 mustAdjacent 字段。\n强相邻需先设计「组块/窗口」模型后再启用。"
                    )
                )
            )
        )
    )

    // 编辑器动作栏：保存/删除以动作值声明，渲染与禁用/显隐由 EditorActionBar 多态编排
    private val actionBar = EditorActionBar(
        stateProperty = headerBar.stateProperty,
        actions = listOf(
            EditorAction.mutation(label = "保存编排") { performSave() },
            EditorAction.editingOnly(label = "删除编排") { performDelete() }
        )
    )

    init {
        padding = Insets(15.0)
        applyEditorContainerStyle()
        maxWidth = 440.0
        minWidth = 340.0

        // A. 配置卡组下拉选择框
        cardGroupPicker.comboBox.apply {
            maxWidth = Double.MAX_VALUE
            converter = object : javafx.util.StringConverter<CardGroupManagerConfig>() {
                override fun toString(obj: CardGroupManagerConfig?): String = obj?.name ?: ""
                override fun fromString(string: String?): CardGroupManagerConfig? = null
            }
            // 判据用自持的 lastManagerId 而非 oldVal：刷新选项列表换出等 id 新实例时不会误清草稿（D-DC-005 过渡语义）
            valueProperty().addListener { _, _, selection ->
                val newId = selection?.cardGroupManagerId
                if (newId != lastManagerId) {
                    lastManagerId = newId
                    // 切换卡组 = 重置勾选：旧卡组的分组 id 对新卡组非法，避免残留 ID 被保存进新卡组
                    selectedCoreGroupIds.clear()
                    selectedDepGroupIds.clear()
                    if (selection != null) repopulateBindingTable(selection) else bindingTable.clear()
                }
            }
        }

        val selectorContainer = VBox(5.0).apply {
            children.addAll(
                SectionTitle("配置目标卡组 (Target Deck):"),
                cardGroupPicker
            )
        }

        // B. 双态表格容器
        val tableContainer = VBox(5.0).apply {
            children.addAll(
                SectionTitle("卡组分组选择 (Core/Dep Config):"),
                bindingTable
            )
        }

        // 声明式状态联动：内容区禁用由状态机驱动（按钮禁用/显隐由 actionBar 内部多态编排）
        editorBox.disableProperty().bind(headerBar.isEditingDisabled)

        editorBox.children.addAll(
            selectorContainer,
            tableContainer,
            paramForm
        )

        children.addAll(headerBar, editorBox, actionBar)
    }

    /**
     * 同步并加载卡组下拉框选项列表
     */
    fun syncAllManagers(managers: List<CardGroupManagerConfig>) {
        cardGroupPicker.setItems(managers, retainSelection = true)
    }

    /**
     * 清空编辑器表单项，回到默认的未选中置灰禁用状态
     */
    /**
     * 按 [EditorState] 相位渲染编辑器。
     *
     * ⚠️ **过渡渲染**：调用方（Workbench）须仅在相位或实体变化时调用（见 `EditorStateTransition`），
     * 同相位重复调用会覆盖用户正在编辑的表单草稿。
     */
    fun render(
        state: EditorState<ComboPlanDefinition>,
        allManagers: List<CardGroupManagerConfig>,
        bindingMap: Map<String, CardGroupBinding>,
        defaultManagerId: String?
    ) {
        headerBar.state = state
        when (state) {
            is EditorState.Empty -> applyEmpty()
            is EditorState.Creating -> applyCreating(allManagers, defaultManagerId)
            is EditorState.Editing -> applyEditing(state.entity, allManagers, bindingMap)
        }
    }

    private fun applyEmpty() {
        cardGroupPicker.clearSelection()
        selectedCoreGroupIds.clear()
        selectedDepGroupIds.clear()
        bindingTable.clear()
        paramForm.reset()
    }

    /** 新建模式：载入当前上下文快照为默认卡组。 */
    private fun applyCreating(allManagers: List<CardGroupManagerConfig>, defaultManagerId: String?) {
        val activeManager = allManagers.find { it.cardGroupManagerId == defaultManagerId }
            ?: allManagers.find { it.enabled }
            ?: allManagers.firstOrNull()

        cardGroupPicker.selectedItem = activeManager

        selectedCoreGroupIds.clear()
        selectedDepGroupIds.clear()
        if (activeManager != null) {
            repopulateBindingTable(activeManager)
        } else {
            bindingTable.clear()
        }

        paramForm.reset()
    }

    /** 编辑模式：加载绑定已有配置数据。 */
    private fun applyEditing(
        plan: ComboPlanDefinition,
        allManagers: List<CardGroupManagerConfig>,
        bindingMap: Map<String, CardGroupBinding>
    ) {
        // 核心高阶 UX：自动回溯推导当前的 Combo 究竟属于哪个卡组配置 (Deck)
        val finalManager = resolveTargetManager(plan, allManagers, bindingMap)

        cardGroupPicker.selectedItem = finalManager

        // 加载选中的角色 ID：按当前卡组过滤 —— 兜底卡组路径下旧卡组的分组 id 对新卡组非法，不能带进来
        val validIds = finalManager?.bindings?.map { it.id }?.toSet()
        val coreIds = plan.coreGroupIds.filter { validIds == null || it in validIds }
        val depIds = plan.depGroupIds.filter { validIds == null || it in validIds }
        selectedCoreGroupIds.clear()
        selectedCoreGroupIds.addAll(coreIds)
        selectedDepGroupIds.clear()
        selectedDepGroupIds.addAll(depIds)

        val droppedIds = (plan.coreGroupIds - coreIds.toSet()) + (plan.depGroupIds - depIds.toSet())
        if (droppedIds.isNotEmpty()) {
            FormPrompt.showProblems(
                listOf(
                    FieldProblem(
                        fieldId = "binding",
                        label = "卡组分组选择",
                        message = "以下分组不属于当前卡组，已取消勾选: $droppedIds"
                    )
                )
            )
        }

        // 加载该卡组下的分组并刷新表格勾选
        if (finalManager != null) {
            repopulateBindingTable(finalManager)
        } else {
            bindingTable.clear()
        }

        // 声明式一键同步表单控制参数
        paramForm.setNumber("score", plan.score)
        paramForm.setNumber("changeScore", plan.changeScore)
        paramForm.setSelect("relation", plan.relation)
        paramForm.setBoolean("coreMutex", plan.coreMutex)
        paramForm.setBoolean("mustAdjacent", plan.mustAdjacent)
    }

    /**
     * 重新构建与加载某个卡组下的 bindings 表格
     */
    private fun repopulateBindingTable(manager: CardGroupManagerConfig) {
        bindingTable.setItems(manager.bindings)
    }

    /**
     * 回溯推导当前 Combo 所属的卡组方案：取首个 core/dep 分组的归属 manager，兜底到启用卡组或首个。
     */
    private fun resolveTargetManager(
        plan: ComboPlanDefinition,
        allManagers: List<CardGroupManagerConfig>,
        bindingMap: Map<String, CardGroupBinding>
    ): CardGroupManagerConfig? {
        val firstGroupId = plan.coreGroupIds.firstOrNull() ?: plan.depGroupIds.firstOrNull()
        val targetManager = if (firstGroupId != null) {
            val targetManagerId = bindingMap[firstGroupId]?.managerId
            allManagers.find { it.cardGroupManagerId == targetManagerId }
        } else null
        return targetManager ?: allManagers.find { it.enabled } ?: allManagers.firstOrNull()
    }

    /**
     * 保存表单数据
     */
    private fun performSave() {
        // 用 when 统一判别 + 取值，消除 as? 与 is 的混用（sealed 状态穷举）
        val editingId = when (val state = headerBar.state) {
            is EditorState.Editing -> state.entity.id
            is EditorState.Creating -> null
            is EditorState.Empty -> return
        }

        onSave(
            ComboPlanFormSnapshot(
                // D-DC-007：本处零校验 —— 未选卡组留空，由保存单点判定并回传提示
                managerId = cardGroupPicker.selectedItem?.cardGroupManagerId ?: "",
                id = editingId,
                coreGroupIds = selectedCoreGroupIds.toSet(),
                depGroupIds = selectedDepGroupIds.toSet(),
                score = paramForm.getNumber("score"),
                changeScore = paramForm.getNumber("changeScore"),
                relation = paramForm.getSelect("relation"),
                coreMutex = paramForm.getBoolean("coreMutex"),
                mustAdjacent = paramForm.getBoolean("mustAdjacent")
            )
        )
    }

    /** 呈现保存单点的校验结果（判定在 `ComboPlanService.save`，此处只渲染 —— D-DC-007）。 */
    fun showSaveProblems(problems: List<ComboPlanProblem>) {
        FormPrompt.showProblems(problems.map { FieldProblem(it.fieldId, it.label, it.message) })
    }

    /**
     * 删除 Combo 编排
     */
    private fun performDelete() {
        val plan = (headerBar.state as? EditorState.Editing)?.entity ?: return
        val confirm = Alert(
            Alert.AlertType.CONFIRMATION,
            "确定要删除该 Combo 编排 (ID: ${plan.id}) 吗？",
            ButtonType.YES,
            ButtonType.NO
        )
        confirm.showAndWait()
        if (confirm.result == ButtonType.YES) {
            onDelete(plan.id)
        }
    }
}
