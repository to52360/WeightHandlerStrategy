package lin.ui.card_group

import javafx.beans.property.ReadOnlyIntegerWrapper
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
import lin.rule.tree.findOverride
import lin.ui.card_group.behavior.BehaviorDisplayMappers
import lin.ui.card_group.behavior.BehaviorEditorPane

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
        val behaviorPane = BehaviorEditorPane(
            store,
            bindingTableView.selectionModel.selectedIndexProperty().lessThan(0)
        )

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
                setCellValueFactory {
                    val stageName = it.value.behaviors.findOverride()?.stageOverride?.name
                    ReadOnlyStringWrapper(if (stageName != null) BehaviorDisplayMappers.stageToLabel(stageName) else "-")
                }
                prefWidth = 120.0
            }
            val colWeight = TableColumn<CardGroupBinding, String>("排序权重").apply {
                setCellValueFactory { ReadOnlyStringWrapper(it.value.behaviors.findOverride()?.orderWeight?.toString() ?: "-") }
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
            children.addAll(toolBar, bindingTableView, behaviorPane.node)
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
            } finally {
                isUpdatingFromState = false
            }
        }
    }
}
