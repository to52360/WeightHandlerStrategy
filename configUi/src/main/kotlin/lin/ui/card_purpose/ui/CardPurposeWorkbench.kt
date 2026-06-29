package lin.ui.card_purpose.ui

import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.bean.usePlan.PurposeTagId

import lin.ui.ActiveAware
import lin.ui.card_purpose.PurposeTagProvider
import lin.ui.card_purpose.db.CardPurposeRepository
import lin.ui.components.PaginationBar
import lin.utils.addColumn
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class CardPurposeWorkbench : SplitPane(), KoinComponent, ActiveAware {

    private val repository: CardPurposeRepository by inject()
    private val tagProvider: PurposeTagProvider by inject()
    private var tagDefs = tagProvider.tags()
    private var displayNameToTagId = tagDefs.associate { it.displayName to it.id }
    private var tagIdToDisplayName = tagDefs.associate { it.id to it.displayName }
    private val store = CardPurposeStore(repository)

    private val tableView = TableView<CardUiItem>()
    private val obsCards = FXCollections.observableArrayList<CardUiItem>()

    // 分页器
    private val paginationBar = PaginationBar { page ->
        store.loadPage(page = page)
    }

    // 左侧顶部工具栏组件
    private val searchField = TextField()
    private val groupCombo = ComboBox<String>()
    private val tagCombo = ComboBox<String>()
    private val dateCombo = ComboBox<String>()

    // 右侧编辑器组件
    private val detailTitle = Label("没有选中卡牌")
    private val editorBox = VBox(15.0)

    // Tag CheckBoxes - 基于 Provider 动态生成
    private val tagCheckBoxes = linkedMapOf<PurposeTagId, CheckBox>().also { map ->
        tagDefs.forEach { tagDef ->
            map[tagDef.id] = CheckBox(tagDef.displayName).apply { isAllowIndeterminate = false }
        }
    }

    private val replanCheck = CheckBox("使用后需要重新规划").apply { isAllowIndeterminate = false }

    private var isUpdatingFromState = false

    init {
        // ==========================================
        // 1. 左侧列表区布局
        // ==========================================
        val leftPanel = VBox(10.0).apply {
            padding = Insets(10.0)
        }

        // 顶部过滤器与工具栏
        val toolBar = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT

            searchField.apply {
                promptText = "搜索 ID 或名称..."
                prefWidth = 160.0
            }

            groupCombo.apply {
                promptText = "全部卡组"
                prefWidth = 130.0
            }

            tagCombo.apply {
                promptText = "全部用途"
                prefWidth = 110.0
                items.addAll(listOf("全部用途", "未配置用途") + tagDefs.map { it.displayName })
                value = "全部用途"
            }

            dateCombo.apply {
                promptText = "全部日期"
                prefWidth = 110.0
                items.add("全部日期")
                value = "全部日期"
            }

            val btnImport = Button("导入卡牌").apply {
                setOnAction { showImportCardGroupDialog() }
            }

            val btnAdd = Button("手动录入").apply {
                setOnAction { showAddCustomCardDialog() }
            }

            children.addAll(searchField, groupCombo, tagCombo, dateCombo, btnImport, btnAdd)
        }

        // 配置 TableView
        tableView.apply {
            selectionModel.selectionMode = SelectionMode.MULTIPLE

            addColumn("卡牌 ID", 120.0) { it.cardId }
            addColumn("卡牌名称", 160.0) { it.name }
            addColumn("用途标签", 220.0) { it.purposeTags.joinToString(", ") { t -> tagIdToDisplayName[t] ?: t.value } }
            addColumn("重规划", 70.0) { if (it.replanAfterUse) "是" else "否" }
            addColumn("录入日期", 100.0) { it.createdDate ?: "-" }

            items = obsCards
        }

        VBox.setVgrow(tableView, Priority.ALWAYS)
        leftPanel.children.addAll(toolBar, tableView, paginationBar)

        // ==========================================
        // 2. 右侧编辑器区布局
        // ==========================================
        val rightPanel = VBox(15.0).apply {
            padding = Insets(20.0, 15.0, 20.0, 15.0)
            style =
                "-fx-background-color: #fafafa; -fx-border-color: #e0e0e0; -fx-border-radius: 8px; -fx-background-radius: 8px;"
            maxWidth = 400.0
            minWidth = 300.0
        }

        detailTitle.apply {
            style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #666; -fx-alignment: center;"
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER
        }

        // 用途选择容器
        val tagsBox = VBox(10.0).apply {
            style =
                "-fx-border-color: #ddd; -fx-border-radius: 6px; -fx-padding: 15px; -fx-background-color: #ffffff; -fx-background-radius: 6px;"
            children.add(Label("用途标签 (PurposeTags)").apply {
                style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-padding: 0 0 5 0;"
            })
            children.addAll(tagCheckBoxes.values)
        }

        // 其他配置容器
        val extraBox = VBox(10.0).apply {
            style =
                "-fx-border-color: #ddd; -fx-border-radius: 6px; -fx-padding: 15px; -fx-background-color: #ffffff; -fx-background-radius: 6px;"
            children.add(Label("高级行为属性").apply {
                style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-padding: 0 0 5 0;"
            })
            children.add(replanCheck)
        }

        // 按钮栏
        val btnSave = Button("保存更改").apply {
            maxWidth = Double.MAX_VALUE
            style =
                "-fx-font-size: 14px; -fx-font-weight: bold; -fx-background-color: #2ecc71; -fx-text-fill: white; -fx-padding: 10;"
            setOnAction { performSave() }
        }

        editorBox.apply {
            children.addAll(tagsBox, extraBox, btnSave)
            isDisable = true
        }

        rightPanel.children.addAll(detailTitle, editorBox)

        // 放入 SplitPane
        this.items.addAll(leftPanel, rightPanel)
        this.setDividerPositions(0.7)

        // ==========================================
        // 3. 事件监听与数据同步
        // ==========================================

        // 左侧 TableView 选择改变监听
        tableView.selectionModel.selectedItems.addListener(ListChangeListener {
            if (!isUpdatingFromState) {
                store.selectCards(tableView.selectionModel.selectedItems.toList())
            }
        })

        // 过滤器组件属性改变监听
        val onFilterChange = {
            val sGroup = if (groupCombo.value == "全部卡组" || groupCombo.value == null) null else groupCombo.value
            val sTag = when (tagCombo.value) {
                "全部用途", null -> null
                "未配置用途" -> PurposeTagId("未配置用途")
                else -> displayNameToTagId[tagCombo.value]
            }
            val sDate = if (dateCombo.value == "全部日期" || dateCombo.value == null) null else dateCombo.value
            store.updateFilters(searchField.text, sGroup, sTag, sDate)
        }

        searchField.textProperty().addListener { _, _, _ -> onFilterChange() }
        groupCombo.valueProperty().addListener { _, _, _ -> onFilterChange() }
        tagCombo.valueProperty().addListener { _, _, _ -> onFilterChange() }
        dateCombo.valueProperty().addListener { _, _, _ -> onFilterChange() }

        // Store State -> UI 响应式订阅
        store.stateProperty().addListener { _, oldState, newState ->
            isUpdatingFromState = true
            try {
                // 1. 同步卡牌列表数据
                if (oldState.currentPageCards != newState.currentPageCards) {
                    obsCards.setAll(newState.currentPageCards)
                }

                // 同步更新分页栏
                paginationBar.update(newState.totalCount, newState.currentPage, newState.pageSize)

                // 2. 同步卡组下拉框选项
                if (oldState.cardGroupFiles != newState.cardGroupFiles) {
                    val comboItems = listOf("全部卡组") + newState.cardGroupFiles
                    groupCombo.items.setAll(comboItems)
                    if (groupCombo.value == null) {
                        groupCombo.value = "全部卡组"
                    }
                }

                // 同步更新日期下拉框选项
                if (oldState.availableDates != newState.availableDates) {
                    val currentVal = dateCombo.value
                    dateCombo.items.setAll(listOf("全部日期") + newState.availableDates)
                    if (currentVal in dateCombo.items) {
                        dateCombo.value = currentVal
                    } else {
                        dateCombo.value = "全部日期"
                    }
                }

                // 3. 同步表格的选中项（支持双向选中）
                val selectedIds = newState.selectedCards.map { it.cardId }.toSet()
                val currentSelectedIds = tableView.selectionModel.selectedItems.map { it.cardId }.toSet()
                if (selectedIds != currentSelectedIds) {
                    tableView.selectionModel.clearSelection()
                    newState.selectedCards.forEach { card ->
                        val index = tableView.items.indexOfFirst { it.cardId == card.cardId }
                        if (index >= 0) {
                            tableView.selectionModel.select(index)
                        }
                    }
                }

                // 4. 驱动右侧编辑器多态/三态渲染
                val selected = newState.selectedCards
                if (selected.isEmpty()) {
                    detailTitle.text = "没有选中卡牌"
                    detailTitle.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #999;"
                    editorBox.isDisable = true

                    tagCheckBoxes.values.forEach {
                        it.isIndeterminate = false
                        it.isSelected = false
                    }
                    replanCheck.isIndeterminate = false
                    replanCheck.isSelected = false
                } else {
                    editorBox.isDisable = false
                    if (selected.size == 1) {
                        // 单选模式
                        val card = selected.first()
                        detailTitle.text = "编辑卡牌: ${card.name}"
                        detailTitle.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2c3e50;"

                        tagCheckBoxes.forEach { (tag, cb) ->
                            cb.isIndeterminate = false
                            cb.isSelected = card.purposeTags.contains(tag)
                        }
                        replanCheck.isIndeterminate = false
                        replanCheck.isSelected = card.replanAfterUse
                    } else {
                        // 批量编辑模式（三态）
                        detailTitle.text = "批量编辑 ${selected.size} 张卡牌"
                        detailTitle.style = "-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #2980b9;"

                        // 处理标签的批量勾选半选状态
                        tagCheckBoxes.forEach { (tag, cb) ->
                            val count = selected.count { it.purposeTags.contains(tag) }
                            if (count == 0) {
                                cb.isIndeterminate = false
                                cb.isSelected = false
                            } else if (count == selected.size) {
                                cb.isIndeterminate = false
                                cb.isSelected = true
                            } else {
                                cb.isIndeterminate = true
                                cb.isSelected = false // 在 JavaFX 中显示为三态中点横杠，必须让 isSelected 为 false
                            }
                        }

                        // 处理重规划的批量勾选半选状态
                        val replanCount = selected.count { it.replanAfterUse }
                        if (replanCount == 0) {
                            replanCheck.isIndeterminate = false
                            replanCheck.isSelected = false
                        } else if (replanCount == selected.size) {
                            replanCheck.isIndeterminate = false
                            replanCheck.isSelected = true
                        } else {
                            replanCheck.isIndeterminate = true
                            replanCheck.isSelected = false
                        }
                    }
                }

            } finally {
                isUpdatingFromState = false
            }
        }

    }

    override fun onActive() {
        // 当视图被主容器激活展示时，安全、按需触发初次数据读取
        store.loadInitialData()
    }

    /**
     * 保存详情编辑数据
     */
    private fun performSave() {
        val selected = store.state.selectedCards
        if (selected.isEmpty()) return

        val cardIds = selected.map { it.cardId }

        // 构建 Tag 应用映射
        // true = 全选，false = 未选，null = 保持半选原状不改变
        val tagsMap = tagCheckBoxes.mapValues { (_, cb) ->
            if (cb.isIndeterminate) null else cb.isSelected
        }

        // 合并 replanAfterUse 三态属性
        val replanVal = if (replanCheck.isIndeterminate) null else replanCheck.isSelected

        store.saveCardPurpose(cardIds, tagsMap, replanVal)
    }

    /**
     * 手动录入新卡弹窗
     */
    private fun showAddCustomCardDialog() {
        val dialog = Dialog<Pair<String, String>>().apply {
            title = "录入自定义新卡牌"
            headerText = "请输入卡牌唯一 ID 和卡牌显示名称"

            val idField = TextField().apply { promptText = "输入卡牌唯一 ID (例如 CS2_024)" }
            val nameField = TextField().apply { promptText = "输入卡牌显示名称 (例如 寒冰箭)" }

            dialogPane.content = GridPane().apply {
                hgap = 10.0
                vgap = 10.0
                padding = Insets(20.0, 100.0, 10.0, 10.0)

                add(Label("卡牌 ID (cardId):"), 0, 0)
                add(idField, 1, 0)
                add(Label("卡牌显示名称:"), 0, 1)
                add(nameField, 1, 1)
            }

            val okButtonType = ButtonType("录入并编辑", ButtonBar.ButtonData.OK_DONE)
            dialogPane.buttonTypes.addAll(okButtonType, ButtonType.CANCEL)

            setResultConverter { buttonType ->
                if (buttonType == okButtonType) {
                    val id = idField.text.trim()
                    val name = nameField.text.trim()
                    if (id.isNotEmpty()) id to name else null
                } else null
            }
        }

        dialog.showAndWait().ifPresent { (id, name) ->
            store.addCustomCard(id, name)
        }
    }

    /**
     * 弹出导入卡组文件窗口
     */
    private fun showImportCardGroupDialog() {
        val files = store.state.cardGroupFiles
        if (files.isEmpty()) {
            Alert(Alert.AlertType.WARNING, "没有检测到任何本地卡组配置文件 (.cardgroup)。").showAndWait()
            return
        }
        val dialog = ChoiceDialog(files.first(), files).apply {
            title = "从本地卡组导入卡牌"
            headerText = "请选择需要导入的 .cardgroup 配置文件"
            contentText = "卡组名称:"
        }
        dialog.showAndWait().ifPresent { selectedFile ->
            store.importCardGroup(selectedFile)
        }
    }
}
