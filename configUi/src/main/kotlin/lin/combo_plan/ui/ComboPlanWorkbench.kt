package lin.combo_plan.ui

import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.bean.usePlan.ComboPlanDefinition
import lin.card_group.db.CardGroupService
import lin.card_group.ui.ActiveManagerHolder
import lin.combo_plan.db.ComboPlanDefinitionRepository
import lin.ui.ActiveAware
import lin.utils.addColumn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ComboPlanWorkbench : SplitPane(), KoinComponent, ActiveAware {

    private var managerListener: javafx.beans.value.ChangeListener<in lin.card_group.db.CardManagerEntity?>? = null

    private val repository: ComboPlanDefinitionRepository by inject()
    private val cardGroupService: CardGroupService by inject()
    private val activeManagerHolder: ActiveManagerHolder by inject()
    private val store = ComboPlanStore(repository, cardGroupService, activeManagerHolder)

    // 左侧工作台组件
    private val tableView = TableView<ComboPlanDefinition>()
    private val obsPlans = FXCollections.observableArrayList<ComboPlanDefinition>()
    private val searchField = TextField()

    // 右侧卡片式编辑器封装组件
    private val editor = ComboPlanEditor()

    private var isUpdatingFromState = false
    private var isCreatingMode = false

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
            val btnAdd = Button("➕ 新建 Combo 编排").apply {
                style = "-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-weight: bold;"
                setOnAction { enterCreatingMode() }
            }
            children.addAll(searchField, btnAdd)
        }

        // 左侧 TableView 列定义
        tableView.apply {
            selectionModel.selectionMode = SelectionMode.SINGLE

            addColumn("ID", 80.0) { it.id }
            addGroupColumn("核心组 (Core Groups)", 180.0) { it.coreGroupIds }
            addGroupColumn("依赖组 (Dep Groups)", 180.0) { it.depGroupIds }
            addColumn("权重", 60.0, isCentered = true) { String.format("%.1f", it.score) }
            addColumn("核心互斥", 60.0, isCentered = true) { if (it.coreMutex) "是" else "否" }
            addColumn("出牌顺序", 90.0) { it.relation.toChineseDesc() }

            items = obsPlans
        }

        VBox.setVgrow(tableView, Priority.ALWAYS)
        leftPanel.children.addAll(toolBar, tableView)

        // =====================================================================
        // 2. 右侧编辑器交互回调绑定
        // =====================================================================
        editor.onSave = { managerId, id, coreSelected, depSelected, score, relation, coreMutex, mustAdjacent ->
            store.savePlan(
                managerId = managerId,
                id = id,
                coreGroupIds = coreSelected,
                depGroupIds = depSelected,
                score = score,
                coreMutex = coreMutex,
                relation = relation,
                mustAdjacent = mustAdjacent
            )
            isCreatingMode = false
        }

        editor.onDelete = { id ->
            store.deletePlan(id)
        }

        // 左右调整 SplitPane 整合
        this.items.addAll(leftPanel, editor)
        this.setDividerPositions(0.60)

        // =====================================================================
        // 3. 事件绑定与状态驱动
        // =====================================================================

        // 表格行选择改变监听
        tableView.selectionModel.selectedItemProperty().addListener { _, _, selection ->
            if (!isUpdatingFromState) {
                isCreatingMode = false
                store.selectPlan(selection)
            }
        }

        // 搜索栏内存模糊检索
        searchField.textProperty().addListener { _, _, text ->
            store.updateFilters(text)
        }

        // 响应状态层更新驱动 UI 刷新
        store.stateProperty().addListener { _, oldState, newState ->
            isUpdatingFromState = true
            try {
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
                        if (!isCreatingMode) {
                            tableView.selectionModel.clearSelection()
                        }
                    } else {
                        val index = tableView.items.indexOfFirst { it.id == newState.selectedPlan.id }
                        if (index >= 0) {
                            tableView.selectionModel.select(index)
                        }
                    }
                }

                // D. 驱动编辑器数据绑定更新
                val selectedPlan = newState.selectedPlan
                if (selectedPlan == null) {
                    if (!isCreatingMode) {
                        editor.clearEditor()
                    }
                } else {
                    isCreatingMode = false
                    editor.loadPlan(selectedPlan, newState.allManagers, newState.bindingMap)
                }
            } finally {
                isUpdatingFromState = false
            }
        }

    }

    override fun onActive() {
        // 当视图被主容器激活展示时，安全、按需触发初次数据读取
        store.loadInitialData()
        // 监听卡组切换，自动刷新数据
        if (managerListener == null) {
            val listener = javafx.beans.value.ChangeListener<lin.card_group.db.CardManagerEntity?> { _, _, _ ->
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
        isCreatingMode = true
        tableView.selectionModel.clearSelection()
        store.selectPlan(null)
        val managerIdSnapshot = activeManagerHolder.activeManagerId
        editor.enterCreatingMode(store.state.allManagers, managerIdSnapshot)
    }

    /**
     * 高内聚抽取：用于渲染分组 ID 集合到前台可读中文名称列表的表格列工厂
     */
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
                val names = ids.mapNotNull { store.state.bindingMap[it]?.name }
                SimpleStringProperty(if (names.isEmpty()) "空" else names.joinToString(", "))
            }
            prefWidth = width
        }
        this.columns.add(column)
        return column
    }
}
