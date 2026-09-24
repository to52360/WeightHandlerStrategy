package lin.ui.combo_plan

import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.bean.usePlan.ComboPlanDefinition
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.CardManagerEntity
import lin.repository.combo_plan.ComboPlanDefinitionRepository
import lin.repository.combo_plan.ComboPlanProblem
import lin.repository.combo_plan.ComboPlanService
import lin.ui.ActiveAware
import lin.ui.GroupDisplay
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.components.state.EditorStateTransition
import lin.utils.addColumn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ComboPlanWorkbench : SplitPane(), KoinComponent, ActiveAware {

    private var managerListener: ChangeListener<in CardManagerEntity?>? = null

    private val repository: ComboPlanDefinitionRepository by inject()
    private val cardGroupService: CardGroupService by inject()
    private val activeManagerHolder: ActiveManagerHolder by inject()
    private val comboPlanService: ComboPlanService by inject()
    private val store = ComboPlanStore(
        repository,
        cardGroupService,
        activeManagerHolder,
        comboPlanService,
        // D-DC-007：判定在保存单点，编辑器只负责呈现。
        // ⚠️ 此处不可在构造 lambda 内直接引用 editor（editor 的回调又引用 store ⇒ 双向类型推断递归），走成员函数间接调用
        onSaveRejected = { problems -> showSaveProblems(problems) }
    )

    // 左侧工作台组件
    private val tableView = TableView<ComboPlanDefinition>()
    private val obsPlans = FXCollections.observableArrayList<ComboPlanDefinition>()
    private val searchField = TextField()

    // 右侧卡片式编辑器封装组件（保存/删除回调构造注入，杜绝 late-set 可空回调）
    private val editor = ComboPlanEditor(
        onSave = { form -> store.savePlan(form) },
        onDelete = { id -> store.deletePlan(id) }
    )

    /**
     * 编辑器状态过渡器：状态变化才推送（不打断用户草稿），用户主动「新建」时 `force` 重置草稿。
     *
     * 机制单点在 `EditorStateTransition`（`D-DC-005`），工作台不自行比较快照。
     */
    private val editorTransition = EditorStateTransition(
        stateProvider = { store.editorState() },
        onTransition = { state ->
            editor.render(
                state = state,
                allManagers = store.state.allManagers,
                bindingMap = store.state.bindingMap,
                defaultManagerId = activeManagerHolder.activeManagerId
            )
        }
    )

    init {
        // =====================================================================
        // 1. 左侧列表区布局
        // =====================================================================
        val leftPanel = VBox(10.0).apply {
            padding = Insets(10.0)
        }

        // 搜索栏和新建按钮
        val toolBar = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            searchField.apply {
                promptText = "搜索组名或 ID..."
                prefWidth = 250.0
                HBox.setHgrow(this, Priority.ALWAYS)
            }
            val btnAdd = Button("新建 Combo 编排").apply {
                style = "-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-weight: bold;"
                setOnAction { enterCreatingMode() }
            }
            children.addAll(searchField, btnAdd)
        }

        // 左侧 TableView 列定义
        tableView.apply {
            selectionModel.selectionMode = SelectionMode.SINGLE

            addColumn("ID", 80.0) { it.id }
            addGroupColumn("核心组", 80.0) { it.coreGroupIds }
            addGroupColumn("依赖组", 80.0) { it.depGroupIds }
            addColumn("费值", 60.0, isCentered = true) { String.format("%.1f", it.score) }
            addColumn("留牌费值", 70.0, isCentered = true) {
                if (it.changeScore == 0.0) "—" else String.format(
                    "%.1f",
                    it.changeScore
                )
            }
            addColumn("核心互斥", 60.0, isCentered = true) { if (it.coreMutex) "是" else "否" }
            addColumn("组合顺序", 90.0) { it.relation.toChineseDesc() }

            items = obsPlans
        }

        VBox.setVgrow(tableView, Priority.ALWAYS)
        leftPanel.children.addAll(toolBar, tableView)

        // 左右调整 SplitPane 整合
        this.items.addAll(leftPanel, editor)
        this.setDividerPositions(0.60)

        // =====================================================================
        // 3. 事件绑定与状态驱动
        // =====================================================================

        // 表格行选择改变监听（幂等回环防护：状态层已是该编排时不再回设，替代原 isUpdatingFromState 抑制）
        tableView.selectionModel.selectedItemProperty().addListener { _, _, selection ->
            if (selection != store.state.selectedPlan) {
                store.selectPlan(selection)
            }
        }

        // 搜索栏内存模糊检索
        searchField.textProperty().addListener { _, _, text ->
            if (text.trim() != store.state.searchText) {
                store.updateFilters(text)
            }
        }

        // 响应状态层更新驱动 UI 刷新
        store.stateProperty().addListener { _, oldState, newState ->
            // A. 同步编辑器可用的卡组下拉选项列表
            if (oldState.allManagers != newState.allManagers) {
                editor.syncAllManagers(newState.allManagers)
            }

            // B. 同步左侧表格源列表
            if (oldState.filteredPlans != newState.filteredPlans) {
                obsPlans.setAll(newState.filteredPlans)
            }

            // C. 同步双向表格选中行
            val currentSelection = tableView.selectionModel.selectedItem
            if (newState.selectedPlan != currentSelection) {
                if (newState.selectedPlan == null) {
                    tableView.selectionModel.clearSelection()
                } else {
                    val index = tableView.items.indexOfFirst { it.id == newState.selectedPlan.id }
                    if (index >= 0) {
                        tableView.selectionModel.select(index)
                    }
                }
            }

            // D. 驱动编辑器：状态机 = store 单一事实源，仅相位/实体变化时过渡渲染（不打断草稿）
            editorTransition.sync()
        }

    }

    /** 转发保存单点的校验结果给编辑器呈现（成员函数形态，避免构造期与 editor 相互推断）。 */
    private fun showSaveProblems(problems: List<ComboPlanProblem>) {
        editor.showSaveProblems(problems)
    }

    override fun onActive() {
        // 当视图被主容器激活展示时，安全、按需触发初次数据读取
        store.loadInitialData()
        // 监听卡组切换，自动刷新数据
        if (managerListener == null) {
            val listener = ChangeListener<CardManagerEntity?> { _, _, _ ->
                store.loadInitialData()
            }
            activeManagerHolder.activeManagerProperty.addListener(listener)
            managerListener = listener
        }
    }

    /**
     * 进入新建 Combo 编排模式，清空表单项以备录入
     */
    private fun enterCreatingMode() {
        store.enterCreatingMode()
        // 用户主动点击「新建」= 显式重置草稿（sync 会因相位未变而幂等跳过，故走意图通道）
        editorTransition.force()
    }

    /**
     * 高内聚抽取：用于渲染分组 ID 集合到前台可读中文名称列表的表格列工厂扩展
     */
    private fun <S> TableView<S>.addGroupColumn(
        title: String,
        width: Double,
        idsExtractor: (S) -> Collection<String>
    ): TableColumn<S, String> {
        val column = TableColumn<S, String>(title).apply {
            setCellValueFactory { cell ->
                val ids = idsExtractor(cell.value)
                // T-007：展示层显式区分成员类型——谓词组加「[谓词组]」标记（成员由条件树定义，无卡列表）
                val names =
                    ids.mapNotNull { id -> store.state.bindingMap[id]?.let { GroupDisplay.displayNameWithType(it) } }
                SimpleStringProperty(if (names.isEmpty()) "空" else names.joinToString(", "))
            }
            prefWidth = width
        }
        this.columns.add(column)
        return column
    }
}
