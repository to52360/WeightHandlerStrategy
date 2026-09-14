package lin.ui.card_group

import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.dao.CardWeightConfig
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.GroupMembership
import lin.rule.tree.findOverride
import lin.ui.GroupDisplay
import lin.ui.card_group.behavior.BehaviorDisplayMappers

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

    // T-TG-018: 策略预设关联面板与卡组微调层面板
    private val presetPane = CardGroupPresetPane()
    private val deltaPanel = DeckDeltaPanel()

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

        // 预设与微调事件绑定
        presetPane.onPresetChanged = { presetId ->
            store.setCardGroupPreset(presetId)
        }

        deltaPanel.onSaveDelta = { exclusions, timings ->
            store.saveDeckDelta(exclusions, timings)
        }

        // 2. 表格与控制栏
        val tableBox = VBox(5.0).apply {
            val toolBar = HBox(5.0).apply {
                alignment = Pos.CENTER_LEFT

                val btnAdd = Button("添加分组").apply {
                    setOnAction { store.addBinding() }
                }
                val btnAddPredicate = Button("🧬 按条件建组").apply {
                    style = "-fx-background-color: #6f42c1; -fx-text-fill: white; -fx-font-weight: bold;"
                    tooltip = Tooltip("用条件树定义组成员（谓词组），无需枚举卡牌")
                    setOnAction { PredicateGroupDialog(store).showAndWait() }
                }
                val btnRemove = Button("移除选中").apply {
                    setOnAction {
                        val idx = bindingTableView.selectionModel.selectedIndex
                        if (idx >= 0) store.dispatch(WorkbenchActions.removeBinding(idx))
                    }
                }
                val btnEditBehavior = Button("⚙️ 行为策略配置").apply {
                    style = "-fx-background-color: #0d6efd; -fx-text-fill: white; -fx-font-weight: bold;"
                    disableProperty().bind(bindingTableView.selectionModel.selectedIndexProperty().lessThan(0))
                    setOnAction {
                        val idx = bindingTableView.selectionModel.selectedIndex
                        if (idx >= 0) {
                            lin.ui.card_group.behavior.GroupBehaviorDialog(store).showAndWait()
                        }
                    }
                }
                children.addAll(btnAdd, btnAddPredicate, btnRemove, btnEditBehavior)
            }

            bindingTableView.isEditable = true

            // 初始化表格列
            val colNo = TableColumn<CardGroupBinding, String>("ID").apply {
                setCellValueFactory { ReadOnlyStringWrapper(it.value.id) }
                prefWidth = 90.0
            }
            val colName = TableColumn<CardGroupBinding, String>("分组名称 (可修改)").apply {
                setCellValueFactory { ReadOnlyStringWrapper(it.value.name) }
                setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn())
                setOnEditCommit { event ->
                    val idx = event.tablePosition.row
                    val newName = event.newValue
                    if (idx in store.state.currentBindings.indices && !newName.isNullOrBlank()) {
                        store.selectBinding(idx)
                        store.updateBindingName(newName)
                    }
                }
                prefWidth = 180.0
            }
            // T-007：成员概要显式区分成员类型——静态组=卡数；谓词组=「条件组」（cardIds 恒空，不能显示 0 张失真）
            val colCardCount = TableColumn<CardGroupBinding, String>("成员").apply {
                setCellValueFactory {
                    val binding = it.value
                    ReadOnlyStringWrapper(GroupDisplay.memberSummary(binding))
                }
                setCellFactory { _ ->
                    object : TableCell<CardGroupBinding, String>() {
                        override fun updateItem(item: String?, empty: Boolean) {
                            super.updateItem(item, empty)
                            if (empty || item == null) {
                                text = null
                                tooltip = null
                            } else {
                                text = item
                                val membership = tableRow?.item?.membership
                                tooltip = if (membership is GroupMembership.Predicate)
                                    Tooltip("成员由条件树定义 (conditionId: ${membership.conditionId})\n运行时判定命中，无需枚举卡牌")
                                else null
                            }
                        }
                    }
                }
                prefWidth = 80.0
            }
            val colStage = TableColumn<CardGroupBinding, String>("阶段覆盖").apply {
                setCellValueFactory {
                    val stageName = it.value.behaviors.findOverride()?.stageOverride?.name
                    ReadOnlyStringWrapper(if (stageName != null) BehaviorDisplayMappers.stageToLabel(stageName) else "-")
                }
                prefWidth = 110.0
            }
            val colWeight = TableColumn<CardGroupBinding, String>("排序权重").apply {
                setCellValueFactory { ReadOnlyStringWrapper(it.value.behaviors.findOverride()?.orderWeight?.toString() ?: "-") }
                prefWidth = 70.0
            }
            val colAction = TableColumn<CardGroupBinding, Void>("操作").apply {
                prefWidth = 110.0
                setCellFactory {
                    object : TableCell<CardGroupBinding, Void>() {
                        private val btn = Button("⚙️ 配置策略").apply {
                            style =
                                "-fx-font-size: 11px; -fx-background-color: #f8f9fa; -fx-border-color: #ced4da; -fx-border-radius: 4;"
                            setOnAction {
                                val binding = tableRow?.item
                                if (binding != null) {
                                    val idx = tableView.items.indexOf(binding)
                                    if (idx >= 0) {
                                        store.selectBinding(idx)
                                        lin.ui.card_group.behavior.GroupBehaviorDialog(store).showAndWait()
                                    }
                                }
                            }
                        }

                        override fun updateItem(item: Void?, empty: Boolean) {
                            super.updateItem(item, empty)
                            graphic = if (empty) null else btn
                        }
                    }
                }
            }
            bindingTableView.columns.addAll(colNo, colName, colCardCount, colStage, colWeight, colAction)

            bindingTableView.items = obsBindings
            bindingTableView.selectionModel.selectedIndexProperty().addListener { _, _, newValue ->
                if (!isUpdatingFromState && store.state.selectedBindingIndex != newValue?.toInt()) {
                    store.selectBinding(newValue?.toInt())
                }
            }

            // 支持双击表格行直接打开策略配置弹窗
            bindingTableView.setRowFactory {
                val row = TableRow<CardGroupBinding>()
                row.setOnMouseClicked { event ->
                    if (event.clickCount == 2 && !row.isEmpty) {
                        val idx = row.index
                        store.selectBinding(idx)
                        lin.ui.card_group.behavior.GroupBehaviorDialog(store).showAndWait()
                    }
                }
                row
            }

            setVgrow(bindingTableView, Priority.ALWAYS)
            children.addAll(toolBar, bindingTableView)
        }

        // 3. 底部选卡区 (左右两栏 SplitPane)
        // T-007：谓词组由条件树定义成员，选中时禁用选卡区并提示条件来源，避免「点了卡却不生效」的静默失真
        val predicateNoteLabel = Label("🧬 谓词组由条件树定义成员，无需手动选卡（成员运行时判定命中）。").apply {
            style =
                "-fx-background-color: #fff3cd; -fx-text-fill: #856404; -fx-padding: 6px; -fx-background-radius: 4px;"
            isWrapText = true
            isVisible = false
            maxWidth = Double.MAX_VALUE
        }

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

        // 垂直分割面板：给表格与选卡区各自分配充足的可调节空间
        val mainSplitPane = SplitPane().apply {
            orientation = javafx.geometry.Orientation.VERTICAL
            items.addAll(tableBox, VBox(predicateNoteLabel, cardSelectPane).apply {
                setVgrow(cardSelectPane, Priority.ALWAYS)
            })
            setDividerPositions(0.45)
        }

        setVgrow(mainSplitPane, Priority.ALWAYS)
        children.addAll(infoBox, presetPane, deltaPanel, mainSplitPane)

        // =====================================
        // State -> UI 更新逻辑
        // =====================================
        store.stateProperty.addListener { _, oldState, newState ->
            isUpdatingFromState = true
            try {
                if (nameField.text != newState.managerName) nameField.text = newState.managerName
                if (enabledCheck.isSelected != newState.managerEnabled) enabledCheck.isSelected =
                    newState.managerEnabled

                // 预设面板状态更新
                presetPane.updateState(
                    currentPresetId = newState.managerPresetId,
                    availablePresets = newState.availablePresets,
                    presetDetail = newState.currentPresetDetail,
                    purposeUniverse = newState.purposeUniverse
                )

                // 卡组微调项面板更新
                // ⚠️ 必须包含 selectedManagerItem：候选树/时序规则是全局的，两卡组 delta 相同（如均为 null）时
                // 其余条件不变 —— 不按卡组切换重载会把上一卡组的未保存勾选泄漏到当前卡组
                if (oldState.selectedManagerItem != newState.selectedManagerItem ||
                    oldState.candidateTrees != newState.candidateTrees ||
                    oldState.timingRules != newState.timingRules ||
                    oldState.currentDeckDelta != newState.currentDeckDelta
                ) {
                    deltaPanel.loadDelta(
                        candidateTrees = newState.candidateTrees,
                        timingRules = newState.timingRules,
                        delta = newState.currentDeckDelta
                    )
                }

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

                // T-007：谓词组选中时禁用选卡区并提示条件来源（成员由条件树运行时判定，手动选卡不生效）
                val selectedBinding = newState.selectedBindingIndex?.let { newState.currentBindings.getOrNull(it) }
                val isPredicate = selectedBinding?.membership is GroupMembership.Predicate
                predicateNoteLabel.isVisible = isPredicate
                cardSelectPane.isDisable = isPredicate
            } finally {
                isUpdatingFromState = false
            }
        }
    }
}
