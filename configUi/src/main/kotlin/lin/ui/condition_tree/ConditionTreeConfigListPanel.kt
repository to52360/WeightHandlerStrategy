package lin.ui.condition_tree

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.CardGroupRepository
import lin.repository.card_group.CardManagerEntity
import lin.ui.card_group.ActiveManagerHolder
import lin.ui.components.TreeConfigStrategy
import lin.ui.condition_tree.action.ConditionTreeWorkbenchAction
import lin.ui.tree_config.TreeModelConverter
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 筛选范围下拉项定义
 */
private data class ScopeFilterItem(
    val id: String?,
    val isAutoLink: Boolean = false,
    val text: String
) {
    override fun toString(): String = text
}

class ConditionTreeConfigListPanel(
    private val workbench: ConditionTreeWorkbench,
    private val treeConfigStrategy: TreeConfigStrategy<lin.rule.condition.ConditionPayload>
) : VBox(6.0), KoinComponent {

    private val cardGroupRepository: CardGroupRepository by inject()
    private val activeManagerHolder: ActiveManagerHolder by inject()

    val configListView = ListView<ConditionTreeListItem>()

    private val scopeFilterCombo = ComboBox<ScopeFilterItem>().apply {
        maxWidth = Double.MAX_VALUE
    }

    private val includeInlineCheckBox = CheckBox("包含内联树 (Inline)").apply {
        isSelected = false
        tooltip = Tooltip("内联条件树是光环/卡组行为等自动生成的私有一次性树，默认隐藏以保持列表整洁")
        style = "-fx-font-size: 11px; -fx-text-fill: #495057;"
    }

    private var isUpdatingFilter = false

    init {
        padding = Insets(10.0)
        val title = Label("条件树列表").apply { style = "-fx-font-weight: bold; -fx-padding: 0 0 4 0;" }

        val buttonBox = FlowPane(5.0, 5.0)
        val actions = getKoin().getAll<ConditionTreeWorkbenchAction>().sortedBy { it.order }
        for (action in actions) {
            val btn = Button(action.title).apply {
                setOnAction { action.execute(workbench) }
            }
            buttonBox.children.add(btn)
        }

        children.addAll(title, scopeFilterCombo, includeInlineCheckBox, buttonBox, configListView)
        VBox.setVgrow(configListView, Priority.ALWAYS)

        setupListView()
        setupFilterListeners()
    }

    private fun setupListView() {
        configListView.setCellFactory {
            object : ListCell<ConditionTreeListItem>() {
                override fun updateItem(item: ConditionTreeListItem?, empty: Boolean) {
                    super.updateItem(item, empty)
                    if (empty || item == null) {
                        text = null
                        graphic = null
                        style = ""
                    } else {
                        val row = HBox(5.0).apply {
                            alignment = Pos.CENTER_LEFT
                        }

                        if (item.isDraft) {
                            row.children.add(Label("* 草稿").apply {
                                style = "-fx-text-fill: #6c757d; -fx-font-style: italic; -fx-font-size: 10px;"
                            })
                        }

                        // 1. 归属范围徽章：全局 vs 卡组私有
                        val scopeBadge = if (item.managerId.isNullOrEmpty()) {
                            Label("全局").apply {
                                style =
                                    "-fx-background-color: #d1e7dd; -fx-text-fill: #0f5132; -fx-padding: 1 5; -fx-background-radius: 3; -fx-font-size: 10px; -fx-font-weight: bold;"
                            }
                        } else {
                            val displayName = item.managerName ?: item.managerId.take(6)
                            Label(displayName).apply {
                                style =
                                    "-fx-background-color: #cfe2ff; -fx-text-fill: #084298; -fx-padding: 1 5; -fx-background-radius: 3; -fx-font-size: 10px; -fx-font-weight: bold;"
                            }
                        }
                        row.children.add(scopeBadge)

                        // 2. 类型徽章：内联一次性树
                        if (item.inlineCreated) {
                            row.children.add(Label("内联").apply {
                                style =
                                    "-fx-background-color: #fff3cd; -fx-text-fill: #664d03; -fx-padding: 1 4; -fx-background-radius: 3; -fx-font-size: 10px;"
                            })
                        }

                        // 3. 条件树名称
                        val nameLabel = Label(item.name).apply {
                            style = if (item.isDraft) "-fx-text-fill: #6c757d; -fx-font-style: italic;" else ""
                            HBox.setHgrow(this, Priority.ALWAYS)
                        }
                        row.children.add(nameLabel)

                        graphic = row
                        text = null
                    }
                }
            }
        }

        configListView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->
            newValue?.let { item ->
                if (!item.isDraft) {
                    val loaded = treeConfigStrategy.loadAll().firstOrNull { it.id == item.id }
                    loaded?.root?.let { rootNode ->
                        workbench.nodeTreeView.root =
                            TreeModelConverter.toTreeItem(rootNode)
                    }
                }
            }
        }
    }

    private fun setupFilterListeners() {
        scopeFilterCombo.setOnAction {
            if (!isUpdatingFilter) {
                refreshList()
            }
        }
        includeInlineCheckBox.setOnAction {
            refreshList()
        }
        activeManagerHolder.activeManagerProperty.addListener { _, _, _ ->
            if (scopeFilterCombo.value?.isAutoLink == true) {
                refreshList()
            }
        }
    }

    fun refreshList() {
        val currentDrafts = configListView.items.filter { it.isDraft }
        configListView.items.clear()

        // 1. 同步卡组下拉选项
        val managers = try {
            cardGroupRepository.findManagers()
        } catch (_: Exception) {
            emptyList()
        }
        val managerMap = managers.associate { it.id to it.name }
        syncScopeFilterItems(managers)

        // 2. 计算当前目标卡组方案与过滤条件
        val currentSelectedFilter = scopeFilterCombo.value
        val activeMId = workbench.targetManagerId ?: activeManagerHolder.activeManagerId

        val targetScopeId: String?
        val isAllScope: Boolean

        if (currentSelectedFilter == null || currentSelectedFilter.isAutoLink) {
            targetScopeId = activeMId
            isAllScope = false
        } else if (currentSelectedFilter.id == "__ALL__") {
            targetScopeId = null
            isAllScope = true
        } else {
            targetScopeId = currentSelectedFilter.id
            isAllScope = false
        }

        val showInline = includeInlineCheckBox.isSelected

        // 3. 加载并过滤配置
        val configs = treeConfigStrategy.loadAll()
        configs.forEach { loaded ->
            val mId = (loaded.extras["managerId"] as? String)?.takeIf { it.isNotBlank() }
            val isInline = (loaded.extras["inlineCreated"] as? Boolean) ?: false

            // 内联树过滤：默认不显示内联树，除非用户明确勾选
            if (!showInline && isInline) {
                return@forEach
            }

            // 范围过滤：
            // - isAllScope == true: 显示所有卡组方案
            // - targetScopeId != null (特定卡组): 显示当前卡组私有树 + 全局共享树
            // - targetScopeId == null (全局未选卡组): 只显示全局共享树 (mId == null)，绝不显示其他卡组私有树/私有内联树！
            val matchesScope = when {
                isAllScope -> true
                targetScopeId != null -> mId == targetScopeId || mId == null
                else -> mId == null
            }

            if (matchesScope) {
                configListView.items.add(
                    ConditionTreeListItem(
                        id = loaded.id,
                        name = loaded.name,
                        isDraft = false,
                        managerId = mId,
                        managerName = mId?.let { managerMap[it] ?: it },
                        inlineCreated = isInline
                    )
                )
            }
        }

        currentDrafts.forEach { configListView.items.add(0, it) }
    }

    private fun syncScopeFilterItems(managers: List<CardManagerEntity>) {
        val prevSelected = scopeFilterCombo.value
        val activeMId = workbench.targetManagerId ?: activeManagerHolder.activeManagerId
        val activeManagerName = activeMId?.let { id -> managers.find { it.id == id }?.name }

        val autoLinkTitle = if (activeManagerName != null) {
            "当前方案: $activeManagerName"
        } else {
            "当前方案: 全局共享 (未选卡组)"
        }

        val items = buildList {
            add(ScopeFilterItem(id = null, isAutoLink = true, text = autoLinkTitle))
            add(ScopeFilterItem(id = null, isAutoLink = false, text = "仅全局共享"))
            for (m in managers) {
                add(ScopeFilterItem(id = m.id, isAutoLink = false, text = "卡组: ${m.name}"))
            }
            add(ScopeFilterItem(id = "__ALL__", isAutoLink = false, text = "全部方案 (所有卡组)"))
        }

        isUpdatingFilter = true
        try {
            scopeFilterCombo.items.setAll(items)
            val matched = if (prevSelected != null && !prevSelected.isAutoLink) {
                items.find { it.id == prevSelected.id && !it.isAutoLink } ?: items.first()
            } else {
                items.first()
            }
            scopeFilterCombo.value = matched
        } finally {
            isUpdatingFilter = false
        }
    }

    fun addDraftItem(name: String): ConditionTreeListItem {
        val currentMId = workbench.targetManagerId ?: activeManagerHolder.activeManagerId
        val managers = try {
            cardGroupRepository.findManagers()
        } catch (_: Exception) {
            emptyList()
        }
        val mName = currentMId?.let { id -> managers.find { it.id == id }?.name }

        val draftItem = ConditionTreeListItem(
            id = "draft_${System.currentTimeMillis()}",
            name = name,
            isDraft = true,
            managerId = currentMId,
            managerName = mName,
            inlineCreated = false
        )
        configListView.items.add(0, draftItem)
        return draftItem
    }
}
