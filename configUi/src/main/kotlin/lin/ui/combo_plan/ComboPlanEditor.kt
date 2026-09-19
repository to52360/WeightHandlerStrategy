package lin.ui.combo_plan

import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.bean.usePlan.ComboPlanDefinition
import lin.bean.usePlan.ComboRelation
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.CardGroupManagerConfig
import lin.ui.GroupDisplay

class ComboPlanEditor : VBox(12.0) {

    private val detailTitle = Label("没有选中 Combo 编排")
    private val editorBox = VBox(12.0)

    // 配置卡组上下文选择器
    private val cardGroupSelector = ComboBox<CardGroupManagerConfig>()

    // 双态合并配置表格
    private val bindingsTableView = TableView<BindingUiRow>()
    private val obsBindingRows = FXCollections.observableArrayList<BindingUiRow>()

    // 配置表单输入项
    private val scoreSpinner = Spinner<Double>(-100.0, 100.0, 0.0, 0.5)
    private val changeScoreSpinner = Spinner<Double>(-100.0, 100.0, 0.0, 0.5)
    private val relationCombo = ComboBox<String>()
    private val coreMutexCheck = CheckBox("核心组同回合硬互斥 (Core Mutex)")
    private val mustAdjacentCheck = CheckBox("必须相邻使用 (Adjacent)")

    // 保存和删除按钮
    private val btnSave = Button("保存编排")
    private val btnDelete = Button("删除编排")

    private var isUpdatingFromState = false
    private var isCreatingMode = false
    private var selectedPlan: ComboPlanDefinition? = null

    // 暴露给外部组件的回调函数
    var onSave: ((form: ComboPlanFormSnapshot) -> Unit)? = null
    var onDelete: ((id: String) -> Unit)? = null

    init {
        padding = Insets(15.0)
        style =
            "-fx-background-color: #fafafa; -fx-border-color: #e0e0e0; -fx-border-radius: 8px; -fx-background-radius: 8px;"
        maxWidth = 440.0
        minWidth = 340.0

        detailTitle.apply {
            style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #7f8c8d; -fx-alignment: center;"
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER
            padding = Insets(0.0, 0.0, 5.0, 0.0)
        }

        // A. 配置卡组下拉选择框
        cardGroupSelector.apply {
            maxWidth = Double.MAX_VALUE
            converter = object : javafx.util.StringConverter<CardGroupManagerConfig>() {
                override fun toString(obj: CardGroupManagerConfig?): String = obj?.name ?: ""
                override fun fromString(string: String?): CardGroupManagerConfig? = null
            }
            valueProperty().addListener { _, _, selection ->
                if (!isUpdatingFromState && selection != null) {
                    repopulateBindingTable(selection)
                }
            }
        }

        val selectorContainer = VBox(5.0).apply {
            children.addAll(
                Label("配置目标卡组 (Target Deck):").apply { style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50;" },
                cardGroupSelector
            )
        }

        // B. 双态合并配置表格配置
        bindingsTableView.apply {
            prefHeight = 350.0
            columnResizePolicy = TableView.UNCONSTRAINED_RESIZE_POLICY

            val colName = TableColumn<BindingUiRow, String>("分组名称 (Binding Name)").apply {
                setCellValueFactory { SimpleStringProperty(it.value.name) }
                prefWidth = 190.0
            }

            val colCore = TableColumn<BindingUiRow, Boolean>("核心 (Core)").apply {
                setCellValueFactory { it.value.coreProperty }
                cellFactory = createCheckBoxColumnCellFactory(bindingsTableView, isCore = true)
                prefWidth = 55.0
                style = "-fx-alignment: CENTER;"
            }

            val colDep = TableColumn<BindingUiRow, Boolean>("依赖 (Dep)").apply {
                setCellValueFactory { it.value.depProperty }
                cellFactory = createCheckBoxColumnCellFactory(bindingsTableView, isCore = false)
                prefWidth = 55.0
                style = "-fx-alignment: CENTER;"
            }

            columns.addAll(colName, colCore, colDep)
            items = obsBindingRows
        }

        val tableContainer = VBox(5.0).apply {
            children.addAll(
                Label("卡组分组选择 (Core/Dep Config):").apply {
                    style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50;"
                },
                bindingsTableView
            )
        }

        // 评分与关系选择区
        scoreSpinner.apply {
            isEditable = true
            prefWidth = 140.0
        }
        val scoreBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("组合评分加权 (费):").apply { style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50;" },
                scoreSpinner
            )
        }

        changeScoreSpinner.apply {
            isEditable = true
            prefWidth = 140.0
            // 起手与出牌是两条独立轴：这里只影响起手换牌，不出牌评分。
            tooltip = Tooltip(
                "起手换牌专用：核心组与依赖组的牌在起手同时保留时，额外加此分。\n" +
                        "用于表达「A、B 单留都一般，一起留才值钱」——单卡 changeWeight 表达不了组合溢价。\n" +
                        "⚠️ 不影响出牌评分（出牌协同加分是上面的「组合评分加权」），同一个 Combo 只计一次。"
            )
        }
        val changeScoreBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("起手组合加分 (费):").apply { style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50;" },
                changeScoreSpinner
            )
        }

        relationCombo.apply {
            items.addAll("仅评分", "核心优先", "依赖优先")
            value = "仅评分"
            prefWidth = 140.0
        }
        val relationBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("执行顺序关系:").apply { style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50;" },
                relationCombo
            )
        }

        // 开关配置区
        val switchesContainer = VBox(8.0).apply {
            style =
                "-fx-border-color: #ddd; -fx-border-radius: 6px; -fx-padding: 10px; -fx-background-color: #ffffff; -fx-background-radius: 6px;"
            children.addAll(
                Label("高级控制行为:").apply {
                    style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-padding: 0 0 4 0;"
                },
                coreMutexCheck,
                mustAdjacentCheck
            )
        }

        // 核心组互斥为出牌/起手双消费字段：取消勾选会连带取消起手换牌的保留互斥，
        // 纯排序配方须显式取消。tooltip 写明连带影响，防配置踩坑。
        coreMutexCheck.tooltip =
            Tooltip(
                "出牌侧：同 Combo 下多个核心组的牌不能同时打出一组。\n" +
                        "纯排序配方（score=0 + 顺序关系）必须取消勾选，否则多核心组被剪枝只剩一组。\n" +
                        "⚠️ 该开关同时作用于起手换牌：取消后起手阶段也不会再阻止这些核心牌同时保留在手中。"
            )

        // T-035：引擎侧 UsePlanOrderer 尚未消费强相邻语义（ComboPlanDefinition.mustAdjacent 仅落库），
        // 勾选不产生任何效果。禁用并说明，避免「配了没反应」被误判为模型失效。
        mustAdjacentCheck.apply {
            isDisable = true
            text = "必须相邻使用 (Adjacent) —— 未实现"
            tooltip =
                Tooltip("引擎当前不支持强相邻约束：UsePlanOrderer 不消费 mustAdjacent 字段。\n强相邻需先设计「组块/窗口」模型后再启用。")
        }

        // 动作按钮区域
        btnSave.apply {
            maxWidth = Double.MAX_VALUE
            style =
                "-fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #2ecc71; -fx-text-fill: white; -fx-padding: 10px;"
            setOnAction { performSave() }
        }

        btnDelete.apply {
            maxWidth = Double.MAX_VALUE
            style =
                "-fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #e74c3c; -fx-text-fill: white; -fx-padding: 10px;"
            setOnAction { performDelete() }
        }

        editorBox.apply {
            children.addAll(
                selectorContainer,
                tableContainer,
                scoreBox,
                changeScoreBox,
                relationBox,
                switchesContainer,
                btnSave,
                btnDelete
            )
            isDisable = true
        }

        children.addAll(detailTitle, editorBox)
    }

    /**
     * 同步并加载卡组下拉框选项列表
     */
    fun syncAllManagers(managers: List<CardGroupManagerConfig>) {
        isUpdatingFromState = true
        try {
            cardGroupSelector.items.setAll(managers)
        } finally {
            isUpdatingFromState = false
        }
    }

    /**
     * 清空编辑器表单项，回到默认的未选中置灰禁用状态
     */
    fun clearEditor() {
        detailTitle.text = "没有选中 Combo 编排"
        detailTitle.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #7f8c8d;"
        editorBox.isDisable = true
        btnDelete.isVisible = false

        obsBindingRows.clear()
        scoreSpinner.valueFactory.value = 0.0
        changeScoreSpinner.valueFactory.value = 0.0
        relationCombo.value = "仅评分"
        coreMutexCheck.isSelected = true
        mustAdjacentCheck.isSelected = false
        selectedPlan = null
        isCreatingMode = false
    }

    /**
     * 新建 Combo 模式，激活编辑器表单并载入当前上下文快照为默认卡组。
     */
    fun enterCreatingMode(allManagers: List<CardGroupManagerConfig>, defaultManagerId: String?) {
        isCreatingMode = true
        selectedPlan = null

        detailTitle.text = "新建 Combo 编排"
        detailTitle.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2ecc71;"
        editorBox.isDisable = false
        btnDelete.isVisible = false

        val activeManager = allManagers.find { it.cardGroupManagerId == defaultManagerId }
            ?: allManagers.find { it.enabled }
            ?: allManagers.firstOrNull()
        isUpdatingFromState = true
        try {
            cardGroupSelector.value = activeManager
        } finally {
            isUpdatingFromState = false
        }

        if (activeManager != null) {
            repopulateBindingTable(activeManager)
        }

        // 初始化新建表单默认值
        scoreSpinner.valueFactory.value = 0.0
        changeScoreSpinner.valueFactory.value = 0.0
        relationCombo.value = "仅评分"
        coreMutexCheck.isSelected = true
        mustAdjacentCheck.isSelected = false
    }

    /**
     * 编辑 Combo 模式，激活编辑器并加载绑定已有配置数据
     */
    fun loadPlan(
        plan: ComboPlanDefinition,
        allManagers: List<CardGroupManagerConfig>,
        bindingMap: Map<String, CardGroupBinding>
    ) {
        isCreatingMode = false
        selectedPlan = plan

        detailTitle.text = "编辑 Combo: ${plan.id}"
        detailTitle.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;"
        editorBox.isDisable = false
        btnDelete.isVisible = true

        // 核心高阶 UX：自动回溯推导当前的 Combo 究竟属于哪个卡组配置 (Deck)
        val firstGroupId = plan.coreGroupIds.firstOrNull() ?: plan.depGroupIds.firstOrNull()
        val targetManager = if (firstGroupId != null) {
            val targetManagerId = bindingMap[firstGroupId]?.managerId
            allManagers.find { it.cardGroupManagerId == targetManagerId }
        } else null

        // 定位卡组切换下拉框，默认到首个激活卡组
        val finalManager = targetManager ?: allManagers.find { it.enabled } ?: allManagers.firstOrNull()

        isUpdatingFromState = true
        try {
            cardGroupSelector.value = finalManager
        } finally {
            isUpdatingFromState = false
        }

        // 加载该卡组下的分组到 bindingsTableView 中，并应用选中状态
        if (finalManager != null) {
            repopulateBindingTable(finalManager)
        }

        // 同步表单控制参数
        scoreSpinner.valueFactory.value = plan.score
        changeScoreSpinner.valueFactory.value = plan.changeScore
        relationCombo.value = plan.relation.toChineseDesc()
        coreMutexCheck.isSelected = plan.coreMutex
        mustAdjacentCheck.isSelected = plan.mustAdjacent
    }

    /**
     * 重新构建与加载某个卡组下的 bindings 表格
     */
    private fun repopulateBindingTable(manager: CardGroupManagerConfig) {
        val rows = manager.bindings.map { binding ->
            val isCore = selectedPlan?.coreGroupIds?.contains(binding.id) == true
            val isDep = selectedPlan?.depGroupIds?.contains(binding.id) == true
            BindingUiRow(
                binding = binding,
                // T-007：展示层显式区分成员类型——谓词组加「[谓词组]」标记
                name = GroupDisplay.displayNameWithType(binding),
                coreProperty = SimpleBooleanProperty(isCore),
                depProperty = SimpleBooleanProperty(isDep)
            )
        }
        obsBindingRows.setAll(rows)
    }

    /**
     * 保存表单数据
     */
    private fun performSave() {
        if (selectedPlan == null && !isCreatingMode) return

        // 收集表格中所有被打上勾的分组 ID
        val coreSelected = obsBindingRows.filter { it.coreProperty.value }.map { it.binding.id }.toSet()
        val depSelected = obsBindingRows.filter { it.depProperty.value }.map { it.binding.id }.toSet()

        if (coreSelected.isEmpty() && depSelected.isEmpty()) {
            Alert(Alert.AlertType.WARNING, "核心组与依赖组不能全部为空！").showAndWait()
            return
        }

        val score = scoreSpinner.value ?: 0.0
        val relation = when (relationCombo.value) {
            "核心优先" -> ComboRelation.CORE_BEFORE_DEP
            "依赖优先" -> ComboRelation.DEP_BEFORE_CORE
            else -> ComboRelation.SCORE_ONLY
        }
        val id = if (isCreatingMode) null else selectedPlan?.id
        val managerId = cardGroupSelector.value?.cardGroupManagerId
        if (managerId.isNullOrBlank()) {
            Alert(Alert.AlertType.WARNING, "请选择目标卡组方案！").showAndWait()
            return
        }
        onSave?.invoke(
            ComboPlanFormSnapshot(
                managerId = managerId,
                id = id,
                coreGroupIds = coreSelected,
                depGroupIds = depSelected,
                score = score,
                changeScore = changeScoreSpinner.value ?: 0.0,
                relation = relation,
                coreMutex = coreMutexCheck.isSelected,
                mustAdjacent = mustAdjacentCheck.isSelected
            )
        )

        isCreatingMode = false
    }

    /**
     * 删除 Combo 编排
     */
    private fun performDelete() {
        val plan = selectedPlan ?: return
        val confirm = Alert(
            Alert.AlertType.CONFIRMATION,
            "确定要删除该 Combo 编排 (ID: ${plan.id}) 吗？",
            ButtonType.YES,
            ButtonType.NO
        )
        confirm.showAndWait()
        if (confirm.result == ButtonType.YES) {
            onDelete?.invoke(plan.id)
        }
    }
}
