package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.ColumnConstraints
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.SurplusOverride
import lin.repository.card_group.ThresholdPatch
import lin.repository.card_group.TimingOverride
import lin.repository.card_purpose.PurposeTagRuleEntity
import lin.repository.tree_config.TreeConfigEntity

/**
 * 单个用途的**声明草稿**（T-TG-037 / D-TG-019）—— 表格行 ↔ 编辑弹窗之间的载体。
 *
 * **声明粒度 = 维度**：三个维度各自二态（声明 = 给出具体值 / 未声明 = 不落库）。
 * 字段级没有「不覆盖」这一层（预设侧非覆盖语义）⇒ 时序四个字段**全显式**（默认值预填），
 * 布尔字段因此不需要三态（`defaultReplanAfterUse` 勾选即真、不勾即假）。
 */
data class PurposeDeclarationDraft(
    val tagId: String,
    /** 该用途的全局默认值行（仅作预填与提示，不参与解析）。 */
    val rule: PurposeTagRuleEntity,
    val declaredTrees: Boolean = false,
    /** 仅 [declaredTrees] 为真时有意义；空集 = 声明为「一棵都不要」。 */
    val treeIds: Set<String> = emptySet(),
    val declaredTiming: Boolean = false,
    val timing: TimingOverride = TimingOverride(),
    val declaredSurplus: Boolean = false,
    /** 仅 [declaredSurplus] 为真时有意义；null = 声明为「不设门槛」。 */
    val surplusThreshold: Int? = null
) {
    companion object {
        /** 由全局默认值**预填全字段**（D-TG-019：预设侧字段不带 presence，勾上声明即为具体值）。 */
        fun fresh(
            rule: PurposeTagRuleEntity,
            declaredTrees: Boolean = false,
            treeIds: Set<String> = emptySet(),
            declaredTiming: Boolean = false,
            declaredSurplus: Boolean = false
        ): PurposeDeclarationDraft = PurposeDeclarationDraft(
            tagId = rule.tagId,
            rule = rule,
            declaredTrees = declaredTrees,
            treeIds = treeIds,
            declaredTiming = declaredTiming,
            timing = TimingOverride(
                defaultStage = rule.defaultStage,
                defaultOrderWeight = rule.defaultOrderWeight,
                defaultReplanAfterUse = rule.defaultReplanAfterUse,
                priority = rule.priority
            ),
            declaredSurplus = declaredSurplus,
            surplusThreshold = rule.defaultSurplusIdleThreshold
        )
    }
}

/**
 * 预设侧「用途 × 声明」聚合表（T-TG-037 / D-TG-019，布局候选 L1）。
 *
 * 取代原来按**维度**分的三个页签（树白名单 / 时序声明 / 惜售声明）—— 同一用途的三条信息
 * 散在不同页签里时，"这个用途声明了没、声明了啥"要来回切，且声明开关与声明内容分离。
 * 现改为**一行一个用途**，三个维度各自「勾选 + 摘要」；细节编辑进 [PresetPurposeEditDialog]（不切页签）。
 *
 * ⚠️ 与卡组侧的 [lin.ui.card_group.DeckPurposeTable]（覆盖语义）**形态相似但语义不同**：
 * 预设侧 = 纯声明（勾 = 落具体值，无"继承"），消费侧 = 覆盖（不勾 = 继承预设/默认）。
 */
class PresetPurposeTable : VBox(6.0) {

    /** **树维度**声明变化（供外层刷新「被禁用用途」汇总：口径 = 全局用途全集 − 树维度已声明的用途）。 */
    var onTreeDeclarationsChanged: ((declaredTags: Set<String>) -> Unit)? = null

    private val grid = GridPane().apply {
        hgap = 10.0
        vgap = 4.0
        padding = Insets(6.0)
        columnConstraints.addAll(
            ColumnConstraints(100.0),                   // 用途
            ColumnConstraints(140.0),                   // 树白名单
            ColumnConstraints(210.0),                   // 时序
            ColumnConstraints(120.0),                   // 惜售
            ColumnConstraints(60.0)                     // 编辑
        )
    }

    private val scrollPane = ScrollPane(grid).apply {
        isFitToWidth = true
        prefHeight = 320.0
        style = "-fx-background-color: transparent;"
    }

    private val emptyLabel = Label("当前数据库中无可声明的内置作用").apply {
        style = "-fx-text-fill: #95a5a6; -fx-padding: 10px;"
        isVisible = false
        isManaged = false
    }

    /** 行草稿（顺序 = 装配顺序；收集时按此顺序产出）。 */
    private val drafts = mutableListOf<PurposeDeclarationDraft>()

    /** 每行的控件引用（改草稿后就地刷新，不重建整表）。 */
    private val rows = mutableMapOf<String, RowControls>()

    private var candidateTreesByTag: Map<String, List<TreeConfigEntity>> = emptyMap()
    private var currentTagDisplayNames: Map<String, String> = emptyMap()

    init {
        padding = Insets(6.0)
        VBox.setVgrow(scrollPane, Priority.ALWAYS)
        children.addAll(scrollPane, emptyLabel)
    }

    /**
     * 装载可声明的用途与已有声明。
     *
     * @param rules 可声明的**作用清单** + 默认值提示（来自 `PresetCatalog.timingRules`，口径见 D-TG-019）
     * @param candidateTrees 全局共享用途树候选
     * @param treeSelections 已声明的树白名单（用途 → 保留的树 id）
     * @param timings 已声明的时序
     * @param surplus 已声明的惜售门槛
     * @param tagDisplayNames 标签中文显示名映射（数据源自 purpose_tag_def 表）
     */
    fun load(
        rules: List<PurposeTagRuleEntity>,
        candidateTrees: List<TreeConfigEntity>,
        treeSelections: Map<String, Set<String>>,
        timings: Map<String, TimingOverride>,
        surplus: Map<String, SurplusOverride>,
        tagDisplayNames: Map<String, String> = emptyMap()
    ) {
        drafts.clear()
        rows.clear()
        grid.children.clear()
        currentTagDisplayNames = tagDisplayNames
        candidateTreesByTag = candidateTrees
            .flatMap { tree -> tree.bindingIdList.map { it to tree } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, trees) -> trees.sortedBy { it.name } }

        val isEmpty = rules.isEmpty()
        emptyLabel.isVisible = isEmpty
        emptyLabel.isManaged = isEmpty
        scrollPane.isVisible = !isEmpty
        scrollPane.isManaged = !isEmpty
        if (isEmpty) return

        addHeaderRow()

        rules.forEachIndexed { index, rule ->
            val timingDeclaration = timings[rule.tagId]
            val surplusDeclaration = surplus[rule.tagId]
            val draft = PurposeDeclarationDraft.fresh(
                rule = rule,
                declaredTrees = treeSelections.containsKey(rule.tagId),
                treeIds = treeSelections[rule.tagId].orEmpty(),
                declaredTiming = timingDeclaration != null,
                declaredSurplus = surplusDeclaration != null
            ).let { base ->
                // 已声明的时序：逐字段取「库中值 > 默认值」—— 库中缺席的字段按"全字段显式"补默认值
                base.copy(
                    timing = if (timingDeclaration == null) base.timing else TimingOverride(
                        defaultStage = timingDeclaration.defaultStage ?: base.timing.defaultStage,
                        defaultOrderWeight = timingDeclaration.defaultOrderWeight,
                        defaultReplanAfterUse = timingDeclaration.defaultReplanAfterUse
                            ?: base.timing.defaultReplanAfterUse,
                        priority = timingDeclaration.priority ?: base.timing.priority
                    ),
                    surplusThreshold = when {
                        surplusDeclaration == null -> base.surplusThreshold
                        else -> surplusDeclaration.surplusIdleThreshold?.value
                    }
                )
            }
            drafts += draft
            addDataRow(index + 1, rule, draft)
        }
        notifyDeclarations()
    }

    // ── 收集（整体替换语义：只回收已声明的维度项）──

    fun collectTreeSelections(): Map<String, Set<String>> =
        drafts.filter { it.declaredTrees }.associate { it.tagId to it.treeIds }

    fun collectTimings(): Map<String, TimingOverride> =
        drafts.filter { it.declaredTiming }.associate { it.tagId to it.timing }

    fun collectSurplus(): Map<String, SurplusOverride> =
        drafts.filter { it.declaredSurplus }
            .associate { it.tagId to SurplusOverride(ThresholdPatch(it.surplusThreshold)) }

    /** 已声明任一维度的用途（口径同 `SqlitePurposeTagIntentRuleProvider` 的声明集合）。 */
    fun declaredTags(): Set<String> =
        drafts.filter { it.declaredTrees || it.declaredTiming || it.declaredSurplus }
            .map { it.tagId }
            .toSet()

    // ── 行装配 ──

    private fun addHeaderRow() {
        listOf("用途", "🌲 树白名单", "⏱️ 出牌时序", "💰 惜售门槛", "编辑").forEachIndexed { col, text ->
            grid.add(
                Label(text).apply {
                    style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-font-size: 11px;"
                },
                col,
                0
            )
        }
    }

    private fun addDataRow(gridRow: Int, rule: PurposeTagRuleEntity, draft: PurposeDeclarationDraft) {
        val displayName = currentTagDisplayNames[rule.tagId] ?: rule.tagId
        val purposeLabel = Label(displayName).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #2c3e50; -fx-cursor: hand;"
            tooltip = javafx.scene.control.Tooltip(rule.tagId)
        }
        val treeLabel = Label(treeSummary(draft)).apply {
            style = dimensionStyle(draft.declaredTrees)
        }
        val timingLabel = Label(timingSummary(draft)).apply {
            style = dimensionStyle(draft.declaredTiming)
        }
        val surplusLabel = Label(surplusSummary(draft)).apply {
            style = dimensionStyle(draft.declaredSurplus)
        }

        val btnEdit = Button("✏️").apply {
            style = "-fx-font-size: 11px; -fx-cursor: hand;"
            setOnAction { openEditor(rule.tagId) }
        }

        // 双击任意文本单元格即可打开编辑弹窗
        listOf(purposeLabel, treeLabel, timingLabel, surplusLabel).forEach { cell ->
            cell.setOnMouseClicked { event ->
                if (event.clickCount == 2) openEditor(rule.tagId)
            }
        }

        rows[rule.tagId] = RowControls(
            treeLabel = treeLabel,
            timingLabel = timingLabel,
            surplusLabel = surplusLabel
        )

        grid.add(purposeLabel, 0, gridRow)
        grid.add(treeLabel, 1, gridRow)
        grid.add(timingLabel, 2, gridRow)
        grid.add(surplusLabel, 3, gridRow)
        grid.add(btnEdit, 4, gridRow)
    }

    private fun dimensionStyle(declared: Boolean): String =
        if (declared) "-fx-font-size: 11px; -fx-text-fill: #2c3e50; -fx-cursor: hand;"
        else "-fx-font-size: 11px; -fx-text-fill: #95a5a6; -fx-cursor: hand;"

    /** 弹窗返回后回写。 */
    private fun openEditor(tagId: String) {
        val index = drafts.indexOfFirst { it.tagId == tagId }
        if (index < 0) return
        val displayName = currentTagDisplayNames[tagId] ?: tagId
        val dialog = PresetPurposeEditDialog(drafts[index], candidateTreesByTag[tagId].orEmpty(), displayName)
        val edited = dialog.showAndWait().orElse(null) ?: return
        drafts[index] = edited
        syncRow(tagId, edited)
        notifyDeclarations()
    }

    /** 就地刷新某行的摘要文本与高亮样式（不重建整表）。 */
    private fun syncRow(tagId: String, draft: PurposeDeclarationDraft) {
        val row = rows[tagId] ?: return
        row.treeLabel.text = treeSummary(draft)
        row.treeLabel.style = dimensionStyle(draft.declaredTrees)
        row.timingLabel.text = timingSummary(draft)
        row.timingLabel.style = dimensionStyle(draft.declaredTiming)
        row.surplusLabel.text = surplusSummary(draft)
        row.surplusLabel.style = dimensionStyle(draft.declaredSurplus)
    }

    private fun notifyDeclarations() = onTreeDeclarationsChanged?.invoke(collectTreeSelections().keys)

    // ── 摘要文案 ──

    private fun treeSummary(draft: PurposeDeclarationDraft): String = when {
        !draft.declaredTrees -> "— (未声明)"
        draft.treeIds.isEmpty() -> "声明为「一棵都不要」"
        else -> "${draft.treeIds.size} 棵"
    }

    private fun timingSummary(draft: PurposeDeclarationDraft): String = if (!draft.declaredTiming) {
        "— (未声明)"
    } else {
        val stage = draft.timing.defaultStage ?: "默认"
        val weight = draft.timing.defaultOrderWeight?.toString() ?: "默认"
        val replan = if (draft.timing.defaultReplanAfterUse == true) "重规划 是" else "重规划 否"
        val priority = draft.timing.priority?.toString() ?: "默认"
        "$stage / 权重$weight / $replan / P$priority"
    }

    private fun surplusSummary(draft: PurposeDeclarationDraft): String = when {
        !draft.declaredSurplus -> "— (未声明)"
        draft.surplusThreshold == null -> "声明为「不设门槛」"
        else -> "门槛 ${draft.surplusThreshold}"
    }

    /** 行内控件引用（就地刷新用，不重建整表）。 */
    private class RowControls(
        val treeLabel: Label,
        val timingLabel: Label,
        val surplusLabel: Label
    )
}
