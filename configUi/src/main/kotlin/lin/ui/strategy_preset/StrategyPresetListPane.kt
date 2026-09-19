package lin.ui.strategy_preset

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.PresetSummary
import lin.utils.addColumn

/**
 * 策略预设工作台左侧列表面板（T-TG-016）。
 *
 * 提供预设列表浏览、状态标记、关键词搜索与新建入口。
 */
class StrategyPresetListPane : VBox(10.0) {

    var onSelectPreset: ((String?) -> Unit)? = null
    var onNewClicked: (() -> Unit)? = null
    var onSearchChanged: ((String) -> Unit)? = null
    var onRefreshClicked: (() -> Unit)? = null

    private val searchField = TextField().apply {
        promptText = "搜索预设名称、ID 或描述..."
        prefWidth = 200.0
    }

    private val btnNew = Button("新建预设").apply {
        style = "-fx-background-color: #27ae60; -fx-text-fill: white; -fx-font-weight: bold;"
    }

    private val btnRefresh = Button("刷新").apply {
        style = "-fx-background-color: #7f8c8d; -fx-text-fill: white;"
    }

    private val tableView = TableView<PresetSummary>()
    private val obsPresets = FXCollections.observableArrayList<PresetSummary>()

    private val lblStatus = Label("共 0 个预设").apply {
        style = "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;"
    }

    private var isUpdatingUi = false
    private var currentActiveDeckId: String? = null

    init {
        padding = Insets(10.0)

        val toolBar = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(searchField, Priority.ALWAYS)
            children.addAll(searchField, btnNew, btnRefresh)
        }

        setupTableView()

        VBox.setVgrow(tableView, Priority.ALWAYS)
        children.addAll(toolBar, tableView, lblStatus)

        setupListeners()
    }

    private fun setupTableView() {
        tableView.apply {
            selectionModel.selectionMode = SelectionMode.SINGLE
            items = obsPresets

            addColumn("ID", 75.0, isCentered = true) { it.preset.id }
            addColumn("预设名称", 140.0) { it.preset.name }
            addColumn("树项", 50.0, isCentered = true) { it.treeItemCount.toString() }
            addColumn("时序", 50.0, isCentered = true) { it.timingCount.toString() }
            addColumn("引用数", 55.0, isCentered = true) { it.referencedBy.size.toString() }
            addColumn("使用状态", 120.0) { summary ->
                val activeDeck = currentActiveDeckId
                when {
                    activeDeck != null && summary.referencedBy.any { it.managerId == activeDeck } -> "★ 当前卡组在用"
                    summary.referencedBy.isEmpty() -> "(未使用)"
                    else -> "已引用 (${summary.referencedBy.size})"
                }
            }
        }
    }

    private fun setupListeners() {
        tableView.selectionModel.selectedItemProperty().addListener { _, _, selection ->
            if (!isUpdatingUi) {
                onSelectPreset?.invoke(selection?.preset?.id)
            }
        }

        searchField.textProperty().addListener { _, _, newText ->
            if (!isUpdatingUi) {
                onSearchChanged?.invoke(newText)
            }
        }

        btnNew.setOnAction { onNewClicked?.invoke() }
        btnRefresh.setOnAction { onRefreshClicked?.invoke() }
    }

    /** 同步最新状态到 UI */
    fun updateState(state: StrategyPresetState) {
        isUpdatingUi = true
        try {
            currentActiveDeckId = state.activeDeckId

            if (searchField.text != state.searchText) {
                searchField.text = state.searchText
            }

            obsPresets.setAll(state.filteredPresets)

            // 保持表格的选中状态与 state.selectedPresetId 一致
            val target = state.filteredPresets.find { it.preset.id == state.selectedPresetId }
            if (target != null) {
                tableView.selectionModel.select(target)
            } else if (state.selectedPresetId == null) {
                tableView.selectionModel.clearSelection()
            }

            lblStatus.text = "共 ${state.allPresets.size} 个预设（展示 ${state.filteredPresets.size} 个）"
        } finally {
            isUpdatingUi = false
        }
    }
}
