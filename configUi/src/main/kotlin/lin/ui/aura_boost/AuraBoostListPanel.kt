package lin.ui.aura_boost

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.aura_boost.AuraBoostEntity
import lin.repository.card_group.CardManagerEntity
import lin.utils.addColumn

class AuraBoostListPanel : VBox(10.0) {

    var onSelectEntity: ((AuraBoostEntity?) -> Unit)? = null
    var onFilterChanged: ((searchText: String, managerId: String?) -> Unit)? = null
    var onNewClicked: (() -> Unit)? = null

    private val searchField = TextField().apply {
        promptText = "搜索名称、ID 或条件树..."
        prefWidth = 180.0
    }

    private val managerFilterCombo = ComboBox<ManagerFilterItem>().apply {
        prefWidth = 160.0
    }

    private val btnAdd = Button("新建 AuraBoost").apply {
        style = "-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-weight: bold;"
    }

    private val tableView = TableView<AuraBoostEntity>()
    private val obsEntities = FXCollections.observableArrayList<AuraBoostEntity>()

    private var conditionTreeMap: Map<String, String> = emptyMap()
    private var managerMap: Map<String, String> = emptyMap()
    private var isUpdatingUi = false

    init {
        padding = Insets(10.0)

        val toolBar = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            HBox.setHgrow(searchField, Priority.ALWAYS)
            children.addAll(searchField, managerFilterCombo, btnAdd)
        }

        setupTableView()

        VBox.setVgrow(tableView, Priority.ALWAYS)
        children.addAll(toolBar, tableView)

        setupListeners()
    }

    private fun setupTableView() {
        tableView.apply {
            selectionModel.selectionMode = SelectionMode.SINGLE
            items = obsEntities

            addColumn("ID", 80.0) { it.id }
            addColumn("名称", 120.0) { it.name ?: "(未命名)" }
            addColumn("触发条件树", 180.0) { entity ->
                val name = conditionTreeMap[entity.conditionId]
                if (name != null) "[${entity.conditionId}] $name" else entity.conditionId
            }
            addColumn("受益过滤条件树", 180.0) { entity ->
                val name = conditionTreeMap[entity.targetConditionId]
                if (name != null) "[${entity.targetConditionId}] $name" else entity.targetConditionId
            }
            addColumn("加分", 60.0, isCentered = true) { String.format("%.1f", it.score) }
            addColumn("归属方案", 120.0) { entity ->
                val mId = entity.managerId
                if (mId.isNullOrEmpty()) "(全局共享)" else managerMap[mId] ?: mId
            }
        }
    }

    private fun setupListeners() {
        tableView.selectionModel.selectedItemProperty().addListener { _, _, selection ->
            if (!isUpdatingUi) {
                onSelectEntity?.invoke(selection)
            }
        }

        searchField.textProperty().addListener { _, _, _ ->
            if (!isUpdatingUi) {
                emitFilterChanged()
            }
        }

        managerFilterCombo.valueProperty().addListener { _, _, _ ->
            if (!isUpdatingUi) {
                emitFilterChanged()
            }
        }

        btnAdd.setOnAction {
            onNewClicked?.invoke()
        }
    }

    private fun emitFilterChanged() {
        val search = searchField.text ?: ""
        val managerId = managerFilterCombo.value?.id
        onFilterChanged?.invoke(search, managerId)
    }

    fun syncManagers(managers: List<CardManagerEntity>, currentFilterId: String?) {
        isUpdatingUi = true
        try {
            managerMap = managers.associate { it.id to it.name }
            val items = mutableListOf<ManagerFilterItem>()
            items.add(ManagerFilterItem(null, "全部卡组方案"))
            for (m in managers) {
                items.add(ManagerFilterItem(m.id, m.name))
            }
            managerFilterCombo.items.setAll(items)

            val matched = items.find { it.id == currentFilterId } ?: items.first()
            managerFilterCombo.value = matched
        } finally {
            isUpdatingUi = false
        }
    }

    fun updateEntities(
        entities: List<AuraBoostEntity>,
        treeMap: Map<String, String>,
        selectedEntity: AuraBoostEntity?
    ) {
        isUpdatingUi = true
        try {
            conditionTreeMap = treeMap
            obsEntities.setAll(entities)
            tableView.refresh()

            if (selectedEntity != null) {
                val idx = obsEntities.indexOfFirst { it.id == selectedEntity.id }
                if (idx >= 0) {
                    tableView.selectionModel.select(idx)
                } else {
                    tableView.selectionModel.clearSelection()
                }
            } else {
                tableView.selectionModel.clearSelection()
            }
        } finally {
            isUpdatingUi = false
        }
    }

    fun clearSelection() {
        isUpdatingUi = true
        try {
            tableView.selectionModel.clearSelection()
        } finally {
            isUpdatingUi = false
        }
    }

}
