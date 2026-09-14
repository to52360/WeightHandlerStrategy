package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.tree_config.TreeConfigEntity

/**
 * 树正向白名单选择面板（T-TG-017）。
 *
 * 按用途分组展示全局共享用途树，提供勾选保留树的能力。
 * 某用途未勾选任何树 = 该用途保留 0 棵树；未出现的用途 = 未声明（全禁）。
 * 供 StrategyPresetDetailPane 使用，且可供 T-TG-018 卡组增量项复用。
 */
class TreeSelectionPanel(
    title: String = "🌲 全局用途树白名单勾选（未声明的用途将被全部关闭）：",
    private val summaryFormatter: ((declaredTagsCount: Int, selectedTreesCount: Int) -> String)? = null
) : VBox(8.0) {

    var onSelectionsChanged: ((declaredTags: Set<String>) -> Unit)? = null

    private val scrollContent = VBox(10.0).apply {
        padding = Insets(6.0)
    }

    private val scrollPane = ScrollPane(scrollContent).apply {
        isFitToWidth = true
        prefHeight = 260.0
        style = "-fx-background-color: transparent;"
    }

    private val summaryLabel = Label("已声明 0 个用途，共保留 0 棵全局用途树").apply {
        style = "-fx-text-fill: #7f8c8d; -fx-font-size: 11px;"
    }

    // tag -> (treeId -> CheckBox)
    private val checkBoxesByTag = mutableMapOf<String, MutableMap<String, CheckBox>>()

    init {
        padding = Insets(6.0)
        VBox.setVgrow(scrollPane, Priority.ALWAYS)
        children.addAll(
            Label(title).apply {
                style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50; -fx-font-size: 12px;"
            },
            scrollPane,
            summaryLabel
        )
    }

    /**
     * 加载候选树与初始选择数据。
     *
     * @param candidates 全局共享用途树全集
     * @param currentSelections 用途 -> 保留的树 ID 集合
     */
    fun loadTrees(
        candidates: List<TreeConfigEntity>,
        currentSelections: Map<String, Set<String>>
    ) {
        scrollContent.children.clear()
        checkBoxesByTag.clear()

        // 按用途 tag 分组候选树
        val treesByTag = mutableMapOf<String, MutableList<TreeConfigEntity>>()
        for (tree in candidates) {
            for (tag in tree.bindingIdList) {
                treesByTag.getOrPut(tag) { mutableListOf() }.add(tree)
            }
        }

        if (treesByTag.isEmpty()) {
            scrollContent.children.add(Label("当前数据库中暂无全局用途树").apply {
                style = "-fx-text-fill: #95a5a6; -fx-padding: 10px;"
            })
            updateSummary()
            return
        }

        // 排序用途
        val sortedTags = treesByTag.keys.sorted()
        for (tag in sortedTags) {
            val trees = treesByTag[tag].orEmpty().sortedBy { it.name }
            val groupCard = createTagGroupCard(tag, trees, currentSelections[tag])
            scrollContent.children.add(groupCard)
        }

        updateSummary()
    }

    private fun createTagGroupCard(
        tag: String,
        trees: List<TreeConfigEntity>,
        selectedTreeIds: Set<String>?
    ): VBox {
        val card = VBox(6.0).apply {
            style = "-fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-background-color: white; -fx-padding: 8px;"
        }

        val tagLabel = Label("🏷️ 用途: $tag").apply {
            style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-font-size: 12px;"
        }

        val btnSelectAll = Button("全选").apply {
            style = "-fx-font-size: 10px; -fx-padding: 2 6;"
        }
        val btnClear = Button("清空").apply {
            style = "-fx-font-size: 10px; -fx-padding: 2 6;"
        }

        val headerBox = HBox(8.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(tagLabel, btnSelectAll, btnClear)
        }

        val treeBox = VBox(4.0).apply {
            padding = Insets(2.0, 0.0, 2.0, 12.0)
        }

        val mapForTag = checkBoxesByTag.getOrPut(tag) { mutableMapOf() }

        for (tree in trees) {
            val isChecked = selectedTreeIds?.contains(tree.id) == true
            val statusHint = if (tree.enabled) "" else " (已禁用)"
            val cb = CheckBox("${tree.name} [id: ${tree.id}]$statusHint").apply {
                isSelected = isChecked
                if (!tree.enabled) {
                    style = "-fx-text-fill: #95a5a6;"
                }
                selectedProperty().addListener { _, _, _ ->
                    updateSummary()
                    notifyChanged()
                }
            }
            mapForTag[tree.id] = cb
            treeBox.children.add(cb)
        }

        btnSelectAll.setOnAction {
            mapForTag.values.forEach { it.isSelected = true }
        }
        btnClear.setOnAction {
            mapForTag.values.forEach { it.isSelected = false }
        }

        card.children.addAll(headerBox, treeBox)
        return card
    }

    /** 收集当前勾选的树白名单（整体替换语义） */
    fun collectTreeSelections(): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        for ((tag, map) in checkBoxesByTag) {
            val selectedIds = map.filter { it.value.isSelected }.keys
            // 该用途下至少勾选 1 棵树才记为声明；0 棵勾选 = 该用途不声明
            if (selectedIds.isNotEmpty()) {
                result[tag] = selectedIds
            }
        }
        return result
    }

    /** 获取当前有声明的用途列表 */
    fun declaredTags(): Set<String> = collectTreeSelections().keys

    private fun updateSummary() {
        val selections = collectTreeSelections()
        val totalDeclaredTags = selections.size
        val totalTrees = selections.values.sumOf { it.size }
        summaryLabel.text = summaryFormatter?.invoke(totalDeclaredTags, totalTrees)
            ?: "已声明 $totalDeclaredTags 个用途，共保留 $totalTrees 棵全局用途树"
    }

    private fun notifyChanged() {
        onSelectionsChanged?.invoke(declaredTags())
    }
}
