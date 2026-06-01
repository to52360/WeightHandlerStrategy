package lin.group_use_override.ui

import javafx.collections.FXCollections
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.control.cell.ComboBoxTableCell
import javafx.scene.control.cell.TextFieldTableCell
import javafx.scene.layout.BorderPane
import javafx.util.Callback
import javafx.util.StringConverter
import javafx.util.converter.DoubleStringConverter
import lin.bean.usePlan.UseStage

/**
 * 分组使用覆盖工作台 —— 单面板 TableView + 顶部工具栏。
 * 分组数据来源：[GroupUseOverrideStore.availableGroups]，取自卡牌分组表。
 */
class GroupUseOverrideWorkbench(
    private val store: GroupUseOverrideStore
) : BorderPane() {

    companion object {
        private fun stageDisplayName(stage: UseStage) = when (stage) {
            UseStage.RESOURCE -> "资源"
            UseStage.SETUP -> "铺垫"
            UseStage.CLEAR -> "解场"
            UseStage.DEFEND -> "保命"
            UseStage.COMBO -> "组合技"
            UseStage.GENERAL -> "价值"
            UseStage.END -> "终结"
        }
    }

    private val tableView = TableView<GroupUseOverrideRow>()

    init {
        // ── 工具栏 ──────────────────────────────────────────────
        val toolBar = ToolBar().apply {
            val btnAdd = Button("新建").apply { setOnAction { store.addRow() } }
            val btnSave = Button("保存全部").apply {
                setOnAction { store.saveAll() }
                disableProperty().bind(store.isDirty.not())
            }
            val btnDelete = Button("删除选中").apply {
                setOnAction {
                    tableView.selectionModel.selectedItem?.let { store.deleteRow(it) }
                }
            }
            val btnRefresh = Button("刷新").apply { setOnAction { store.loadAll() } }
            items.addAll(btnAdd, btnSave, btnDelete, btnRefresh)
        }

        // ── 列定义 ──────────────────────────────────────────────
        val groupCol = createGroupColumn()
        val stageCol = createStageColumn()
        val replanCol = createReplanColumn()
        val weightCol = createWeightColumn()

        tableView.columns.setAll(groupCol, stageCol, replanCol, weightCol)
        tableView.isEditable = true
        tableView.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
        tableView.items = store.rows

        top = toolBar
        center = tableView
        setMargin(tableView, Insets(5.0))
    }

    // ════════════════════════════════════════════════════════════
    // cardGroupId 列 —— 下拉选择现有分组
    // ════════════════════════════════════════════════════════════
    private fun createGroupColumn(): TableColumn<GroupUseOverrideRow, String> {
        return TableColumn<GroupUseOverrideRow, String>("所属分组").apply {
            cellValueFactory =
                Callback { data: TableColumn.CellDataFeatures<GroupUseOverrideRow, String> -> data.value.cardGroupId }
            cellFactory = Callback { _ ->
                val cell = object : TableCell<GroupUseOverrideRow, String>() {
                    private val combo = ComboBox<GroupOption>()

                    init {
                        combo.isEditable = false
                        combo.setOnAction {
                            if (combo.value != null) {
                                commitEdit(combo.value.bindingId)
                            }
                        }
                    }

                    override fun updateItem(item: String?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty || item == null) {
                            text = null
                            graphic = null
                        } else {
                            text = store.availableGroups.find { it.bindingId == item }?.displayName ?: item
                            graphic = null
                        }
                    }

                    override fun startEdit() {
                        super.startEdit()
                        combo.items = store.availableGroups
                        combo.value = store.availableGroups.find { it.bindingId == item }
                        graphic = combo
                        text = null
                    }

                    override fun cancelEdit() {
                        super.cancelEdit()
                        updateItem(item, isEmpty)
                    }

                    override fun commitEdit(newValue: String) {
                        super.commitEdit(newValue)
                        // commit 后 updateItem 会被重新调用，恢复为 label 展示
                    }
                }
                cell
            }
            setOnEditCommit { event ->
                event.rowValue.cardGroupId.set(event.newValue)
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // stageOverride 列 —— ComboBox 选择 UseStage
    // ════════════════════════════════════════════════════════════
    private fun createStageColumn(): TableColumn<GroupUseOverrideRow, UseStage?> {
        val stages = FXCollections.observableArrayList<UseStage?>()
        stages.addAll(null, *UseStage.entries.toTypedArray())

        val converter = object : StringConverter<UseStage?>() {
            override fun toString(value: UseStage?) = value?.let { stageDisplayName(it) } ?: "(不覆盖)"
            override fun fromString(string: String?) = null
        }

        return TableColumn<GroupUseOverrideRow, UseStage?>("出牌阶段").apply {
            cellValueFactory =
                Callback { data: TableColumn.CellDataFeatures<GroupUseOverrideRow, UseStage?> -> data.value.stageOverride }
            cellFactory = ComboBoxTableCell.forTableColumn<GroupUseOverrideRow, UseStage?>(converter, stages)
            setOnEditCommit { event ->
                event.rowValue.stageOverride.set(event.newValue)
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // replanAfterUse 列 —— ComboBox 三态选择
    // ════════════════════════════════════════════════════════════
    private fun createReplanColumn(): TableColumn<GroupUseOverrideRow, Boolean?> {
        val triStates = FXCollections.observableArrayList(TriState.entries.toList())

        return TableColumn<GroupUseOverrideRow, Boolean?>("出牌后重规划").apply {
            cellValueFactory =
                Callback { data: TableColumn.CellDataFeatures<GroupUseOverrideRow, Boolean?> -> data.value.replanAfterUse }
            cellFactory = Callback {
                object : TableCell<GroupUseOverrideRow, Boolean?>() {
                    private val combo = ComboBox<TriState>()

                    init {
                        combo.items = triStates
                        combo.setOnAction {
                            if (combo.value != null) {
                                commitEdit(combo.value.value)
                            }
                        }
                    }

                    override fun updateItem(item: Boolean?, empty: Boolean) {
                        super.updateItem(item, empty)
                        if (empty) {
                            text = null
                            graphic = null
                        } else {
                            val ts = TriState.entries.find { it.value == item }
                            text = ts?.label ?: "不覆盖"
                            graphic = null
                        }
                    }

                    override fun startEdit() {
                        super.startEdit()
                        combo.value = TriState.entries.find { it.value == item }
                        graphic = combo
                        text = null
                    }

                    override fun cancelEdit() {
                        super.cancelEdit()
                        updateItem(item, isEmpty)
                    }
                }
            }
            setOnEditCommit { event ->
                event.rowValue.replanAfterUse.set(event.newValue)
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    // orderWeight 列 —— 数字文本编辑
    // ════════════════════════════════════════════════════════════
    private fun createWeightColumn(): TableColumn<GroupUseOverrideRow, Number> {
        return TableColumn<GroupUseOverrideRow, Number>("排序权重").apply {
            cellValueFactory =
                Callback { data: TableColumn.CellDataFeatures<GroupUseOverrideRow, Number> -> data.value.orderWeight }
            cellFactory =
                TextFieldTableCell.forTableColumn(
                    DoubleStringConverter() as StringConverter<Number>
                )
            setOnEditCommit { event ->
                event.rowValue.orderWeight.set(event.newValue.toDouble())
            }
        }
    }
}
