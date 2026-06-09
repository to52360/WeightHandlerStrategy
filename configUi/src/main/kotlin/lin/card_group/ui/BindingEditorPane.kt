package lin.card_group.ui

import javafx.beans.property.ReadOnlyIntegerWrapper
import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.bean.usePlan.UseStage
import lin.dao.CardWeightConfig
import lin.rule.tree.CardGroupBinding

/**
 * 右侧：Binding 详情编辑面板
 */
class BindingEditorPane(private val store: WorkbenchStore) : VBox(10.0) {

    private val bindingTableView = TableView<CardGroupBinding>()
    private val cardPoolListView = ListView<CardWeightConfig>()
    private val selectedCardListView = ListView<String>()

    // 内部的 Observable 数据源，用于 JavaFX 绑定
    private val obsBindings = FXCollections.observableArrayList<CardGroupBinding>()
    private val obsCardPool = FXCollections.observableArrayList<CardWeightConfig>()
    private val obsSelectedCards = FXCollections.observableArrayList<String>()

    // 防止在监听属性变化时触发循环调用
    private var isUpdatingFromState = false

    init {
        padding = Insets(10.0)

        // 1. Manager 基础配置区
        val nameField = TextField().apply { promptText = "分组方案名称" }
        val enabledCheck = CheckBox("启用")
        val infoBox = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(Label("方案名称:"), nameField, enabledCheck)
        }

        // 监听输入，触发 Action
        nameField.textProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState) store.dispatch(
                WorkbenchActions.updateManagerInfo(
                    newValue,
                    enabledCheck.isSelected
                )
            )
        }
        enabledCheck.selectedProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState) store.dispatch(WorkbenchActions.updateManagerInfo(nameField.text, newValue))
        }

        // 2. 表格与控制栏
        val bindingNameField = TextField().apply {
            promptText = "分组名称"
            // 未选中时禁用
            disableProperty().bind(bindingTableView.selectionModel.selectedIndexProperty().lessThan(0))
        }

        val tableBox = VBox(5.0).apply {
            val toolBar = HBox(5.0).apply {
                alignment = Pos.CENTER_LEFT

                val btnAdd = Button("添加分组").apply {
                    setOnAction { store.addBinding() }
                }
                val btnRemove = Button("移除选中").apply {
                    setOnAction {
                        val idx = bindingTableView.selectionModel.selectedIndex
                        if (idx >= 0) store.dispatch(WorkbenchActions.removeBinding(idx))
                    }
                }
                children.addAll(btnAdd, btnRemove)
            }

            val bindingInfoBox = VBox(5.0).apply {
                // 第一行：分组名称
                val nameRow = HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(Label("分组名称:"), bindingNameField)
                }

                // 第二行：行为属性编辑
                val stageCombo = ComboBox<String>().apply {
                    items.setAll(
                        listOf("(不覆盖)") + UseStage.entries.map { it.name }
                    )
                    promptText = "出牌阶段覆盖"
                    disableProperty().bind(bindingTableView.selectionModel.selectedIndexProperty().lessThan(0))
                }
                val replanCombo = ComboBox<String>().apply {
                    items.setAll("(不覆盖)", "是", "否")
                    promptText = "出牌后重规划"
                    disableProperty().bind(bindingTableView.selectionModel.selectedIndexProperty().lessThan(0))
                }
                val weightField = TextField().apply {
                    promptText = "排序权重"
                    prefWidth = 80.0
                    disableProperty().bind(bindingTableView.selectionModel.selectedIndexProperty().lessThan(0))
                }

                val behaviorRow = HBox(10.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("阶段覆盖:"), stageCombo,
                        Label("重规划:"), replanCombo,
                        Label("排序权重:"), weightField
                    )
                }

                children.addAll(nameRow, behaviorRow)

                // ── 行为属性事件绑定 ──
                bindingNameField.textProperty().addListener { _, _, newValue ->
                    if (!isUpdatingFromState && newValue != null) {
                        store.updateBindingName(newValue)
                    }
                }

                stageCombo.valueProperty().addListener { _, _, newValue ->
                    if (!isUpdatingFromState && newValue != null) {
                        val stage = if (newValue == "(不覆盖)") null else newValue
                        store.updateBindingStageOverride(stage)
                    }
                }

                replanCombo.valueProperty().addListener { _, _, newValue ->
                    if (!isUpdatingFromState && newValue != null) {
                        val replan = when (newValue) {
                            "是" -> true
                            "否" -> false
                            else -> null
                        }
                        store.updateBindingReplanAfterUse(replan)
                    }
                }

                weightField.textProperty().addListener { _, _, newValue ->
                    if (!isUpdatingFromState && newValue != null) {
                        newValue.toDoubleOrNull()?.let { store.updateBindingOrderWeight(it) }
                    }
                }

                // ── 从 State 同步到编辑控件 ──
                store.stateProperty.addListener { _, oldState, newState ->
                    if (oldState.currentBindings != newState.currentBindings || oldState.selectedBindingIndex != newState.selectedBindingIndex) {
                        val idx = newState.selectedBindingIndex
                        if (idx != null && idx in newState.currentBindings.indices) {
                            val binding = newState.currentBindings[idx]
                            val stageVal = binding.overrides?.stageOverride?.name
                            if (stageCombo.value != (stageVal ?: "(不覆盖)")) {
                                stageCombo.value = stageVal ?: "(不覆盖)"
                            }
                            val replanDisplay = when (binding.overrides?.replanAfterUse) {
                                true -> "是"
                                false -> "否"
                                null -> "(不覆盖)"
                            }
                            if (replanCombo.value != replanDisplay) {
                                replanCombo.value = replanDisplay
                            }
                            val weightVal = binding.overrides?.orderWeight
                            val weightStr = weightVal?.toString() ?: ""
                            if (weightField.text != weightStr) {
                                weightField.text = weightStr
                            }
                        } else {
                            stageCombo.value = null
                            replanCombo.value = null
                            weightField.clear()
                        }
                    }
                }
            }

            // 初始化表格列
            val colNo = TableColumn<CardGroupBinding, String>("ID").apply {
                setCellValueFactory { ReadOnlyStringWrapper(it.value.id) }
                prefWidth = 100.0
            }
            val colName = TableColumn<CardGroupBinding, String>("分组名称").apply {
                setCellValueFactory { ReadOnlyStringWrapper(it.value.name) }
                prefWidth = 200.0
            }
            val colCardCount = TableColumn<CardGroupBinding, Number>("已选卡数").apply {
                setCellValueFactory { ReadOnlyIntegerWrapper(it.value.cardIds.size) }
                prefWidth = 80.0
            }
            val colStage = TableColumn<CardGroupBinding, String>("阶段覆盖").apply {
                setCellValueFactory { ReadOnlyStringWrapper(it.value.overrides?.stageOverride?.name ?: "-") }
                prefWidth = 80.0
            }
            val colWeight = TableColumn<CardGroupBinding, String>("排序权重").apply {
                setCellValueFactory { ReadOnlyStringWrapper(it.value.overrides?.orderWeight?.toString() ?: "-") }
                prefWidth = 70.0
            }
            bindingTableView.columns.addAll(colNo, colName, colCardCount, colStage, colWeight)

            bindingTableView.items = obsBindings
            bindingTableView.selectionModel.selectedIndexProperty().addListener { _, _, newValue ->
                if (!isUpdatingFromState && store.state.selectedBindingIndex != newValue?.toInt()) {
                    store.selectBinding(newValue?.toInt())
                }
            }

            setVgrow(bindingTableView, Priority.ALWAYS)
            children.addAll(toolBar, bindingTableView, bindingInfoBox)
        }

        // 3. 底部选卡区 (左右两栏 SplitPane)
        val cardSelectPane = SplitPane().apply {
            cardPoolListView.items = obsCardPool
            // 格式化卡池列表的显示
            cardPoolListView.setCellFactory {
                object : ListCell<CardWeightConfig>() {
                    override fun updateItem(item: CardWeightConfig?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else "${item.name} (${item.cardId})"
                    }
                }
            }

            selectedCardListView.items = obsSelectedCards

            cardPoolListView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
                if (!isUpdatingFromState && newValue != null) {
                    store.dispatch(WorkbenchActions.toggleCard(newValue.cardId, true))
                }
            }

            selectedCardListView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
                if (!isUpdatingFromState && newValue != null) {
                    store.dispatch(WorkbenchActions.toggleCard(newValue, false))
                }
            }

            items.addAll(
                VBox(Label("卡池 (点击添加)"), cardPoolListView).apply {
                    setVgrow(
                        cardPoolListView,
                        Priority.ALWAYS
                    )
                },
                VBox(Label("已选卡牌 (点击移除)"), selectedCardListView).apply {
                    setVgrow(
                        selectedCardListView,
                        Priority.ALWAYS
                    )
                }
            )
        }

        setVgrow(tableBox, Priority.ALWAYS)
        setVgrow(cardSelectPane, Priority.ALWAYS)
        children.addAll(infoBox, tableBox, cardSelectPane)

        // =====================================
        // State -> UI 更新逻辑
        // =====================================
        store.stateProperty.addListener { _, oldState, newState ->
            isUpdatingFromState = true
            try {
                if (nameField.text != newState.managerName) nameField.text = newState.managerName
                if (enabledCheck.isSelected != newState.managerEnabled) enabledCheck.isSelected =
                    newState.managerEnabled

                if (oldState.currentBindings != newState.currentBindings) {
                    obsBindings.setAll(newState.currentBindings)
                }

                if (bindingTableView.selectionModel.selectedIndex != newState.selectedBindingIndex) {
                    newState.selectedBindingIndex?.let {
                        bindingTableView.selectionModel.select(it)
                    } ?: bindingTableView.selectionModel.clearSelection()
                }

                if (oldState.currentCardPool != newState.currentCardPool) {
                    obsCardPool.setAll(newState.currentCardPool)
                }

                if (oldState.selectedCards != newState.selectedCards) {
                    obsSelectedCards.setAll(newState.selectedCards)
                }

                // 同步当前选中的分组名称到输入框
                val selectedIdx = newState.selectedBindingIndex
                if (selectedIdx != null && selectedIdx in newState.currentBindings.indices) {
                    val currentName = newState.currentBindings[selectedIdx].name
                    if (bindingNameField.text != currentName) {
                        bindingNameField.text = currentName
                    }
                } else {
                    bindingNameField.clear()
                }
            } finally {
                isUpdatingFromState = false
            }
        }
    }
}
