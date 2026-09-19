package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
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
    /** 是否声明了至少一个维度 */
    val isDeclared: Boolean get() = declaredTrees || declaredTiming || declaredSurplus

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

/** 表格视图过滤模式 */
enum class PurposeFilterMode {
    DECLARED_ONLY,      // 仅显示已配置用途（默认推荐）
    UNDECLARED_ONLY,    // 仅显示未配置候选用途
    ALL                 // 显示全部用途
}

/**
 * 预设侧「用途 × 声明」聚合表（T-TG-037 / D-TG-019）。
 *
 * 升级优化：
 * 1. 支持按需查看（默认【仅看已声明】，消除死行视觉噪音）；
 * 2. 支持快速一键声明未配置用途、一键清空/移除已有声明；
 * 3. 优化宽屏列宽，消除文本截断。
 */
class PresetPurposeTable : VBox(6.0) {

    /** **树维度**声明变化（供外层刷新「被禁用用途」汇总：口径 = 全局用途全集 − 树维度已声明的用途）。 */
    var onTreeDeclarationsChanged: ((declaredTags: Set<String>) -> Unit)? = null

    /** 声明发生变动（新增、修改或移除），通知宿主刷新候选下拉框等 */
    var onDeclarationsChanged: (() -> Unit)? = null

    /** 视图过滤模式（默认仅显示已配置的用途） */
    var filterMode: PurposeFilterMode = PurposeFilterMode.DECLARED_ONLY
        set(value) {
            field = value
            renderRows()
        }

    private val grid = GridPane().apply {
        hgap = 12.0
        vgap = 6.0
        padding = Insets(6.0)
        columnConstraints.addAll(
            ColumnConstraints(110.0),                   // 用途
            ColumnConstraints(160.0),                   // 树白名单
            ColumnConstraints(260.0),                   // 时序
            ColumnConstraints(130.0),                   // 惜售
            ColumnConstraints(90.0)                     // 操作
        )
    }

    private val scrollPane = ScrollPane(grid).apply {
        isFitToWidth = true
        prefHeight = 340.0
        style = "-fx-background-color: transparent;"
    }

    private val emptyLabel = Label().apply {
        style = "-fx-text-fill: #95a5a6; -fx-padding: 24px; -fx-font-size: 12px; -fx-alignment: center;"
        isVisible = false
        isManaged = false
    }

    /** 全部用途的行草稿（顺序 = 装配顺序；收集时按此顺序产出）。 */
    private val drafts = mutableListOf<PurposeDeclarationDraft>()

    private var candidateTreesByTag: Map<String, List<TreeConfigEntity>> = emptyMap()
    private var currentTagDisplayNames: Map<String, String> = emptyMap()

    init {
        padding = Insets(6.0)
        VBox.setVgrow(scrollPane, Priority.ALWAYS)
        children.addAll(scrollPane, emptyLabel)
    }

    /**
     * 装载可声明的用途与已有声明。
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
        currentTagDisplayNames = tagDisplayNames
        candidateTreesByTag = candidateTrees
            .flatMap { tree -> tree.bindingIdList.map { it to tree } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, trees) -> trees.sortedBy { it.name } }

        rules.forEach { rule ->
            val timingDeclaration = timings[rule.tagId]
            val surplusDeclaration = surplus[rule.tagId]
            val draft = PurposeDeclarationDraft.fresh(
                rule = rule,
                declaredTrees = treeSelections.containsKey(rule.tagId),
                treeIds = treeSelections[rule.tagId].orEmpty(),
                declaredTiming = timingDeclaration != null,
                declaredSurplus = surplusDeclaration != null
            ).let { base ->
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
        }

        renderRows()
        notifyDeclarations()
    }

    /** 获取所有未声明配置的用途选项 (tagId to displayName) */
    fun getUndeclaredTagOptions(): List<Pair<String, String>> =
        drafts.filter { !it.isDeclared }
            .map { it.tagId to (currentTagDisplayNames[it.tagId] ?: it.tagId) }

    /** 获取所有已声明配置的用途选项 (tagId to displayName) */
    fun getDeclaredTagOptions(): List<Pair<String, String>> =
        drafts.filter { it.isDeclared }
            .map { it.tagId to (currentTagDisplayNames[it.tagId] ?: it.tagId) }

    /** 快速打开特定用途的编辑弹窗（常用于下拉选择后一键激活） */
    fun openEditorForTag(tagId: String) {
        openEditor(tagId)
    }

    /** 清除某用途的全部声明（重置为未声明） */
    fun clearDeclaration(tagId: String) {
        val index = drafts.indexOfFirst { it.tagId == tagId }
        if (index < 0) return
        val current = drafts[index]
        drafts[index] = PurposeDeclarationDraft.fresh(current.rule)
        renderRows()
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

    /** 已声明任一维度的用途集合 */
    fun declaredTags(): Set<String> =
        drafts.filter { it.isDeclared }.map { it.tagId }.toSet()

    // ── 渲染与排版 ──

    private fun renderRows() {
        grid.children.clear()

        val visibleDrafts = when (filterMode) {
            PurposeFilterMode.DECLARED_ONLY -> drafts.filter { it.isDeclared }
            PurposeFilterMode.UNDECLARED_ONLY -> drafts.filter { !it.isDeclared }
            PurposeFilterMode.ALL -> drafts
        }

        if (visibleDrafts.isEmpty()) {
            scrollPane.isVisible = false
            scrollPane.isManaged = false
            emptyLabel.isVisible = true
            emptyLabel.isManaged = true

            emptyLabel.text = when (filterMode) {
                PurposeFilterMode.DECLARED_ONLY ->
                    "当前预设尚未声明任何用途（所有全局用途树均关闭兜底）。\n可在上方「➕ 添加用途声明」下拉框中选择要定制的用途。"
                PurposeFilterMode.UNDECLARED_ONLY ->
                    "所有战略用途均已完成声明配置（无未声明候选）。"
                PurposeFilterMode.ALL ->
                    "当前数据库中无可声明的战略用途。"
            }
            return
        }

        scrollPane.isVisible = true
        scrollPane.isManaged = true
        emptyLabel.isVisible = false
        emptyLabel.isManaged = false

        addHeaderRow()

        visibleDrafts.forEachIndexed { index, draft ->
            addDataRow(index + 1, draft)
        }
    }

    private fun addHeaderRow() {
        listOf("战略用途", "树白名单", "出牌时序", "惜售门槛", "操作").forEachIndexed { col, text ->
            grid.add(
                Label(text).apply {
                    style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-font-size: 11px;"
                },
                col,
                0
            )
        }
    }

    private fun addDataRow(gridRow: Int, draft: PurposeDeclarationDraft) {
        val displayName = currentTagDisplayNames[draft.tagId] ?: draft.tagId
        val purposeLabel = Label(displayName).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #2c3e50; -fx-cursor: hand;"
            tooltip = Tooltip(draft.tagId)
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

        // 双击任意文本单元格即可打开编辑弹窗
        listOf(purposeLabel, treeLabel, timingLabel, surplusLabel).forEach { cell ->
            cell.setOnMouseClicked { event ->
                if (event.clickCount == 2) openEditor(draft.tagId)
            }
        }

        val actionBox = HBox(6.0).apply {
            alignment = Pos.CENTER_LEFT
            if (draft.isDeclared) {
                val btnEdit = Button("编辑").apply {
                    style = "-fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 2 8;"
                    tooltip = Tooltip("编辑该用途的三维度声明")
                    setOnAction { openEditor(draft.tagId) }
                }
                val btnRemove = Button("移除").apply {
                    style = "-fx-font-size: 11px; -fx-cursor: hand; -fx-text-fill: #c0392b; -fx-padding: 2 8;"
                    tooltip = Tooltip("清空该用途的全部声明，使其变回未声明状态")
                    setOnAction { clearDeclaration(draft.tagId) }
                }
                children.addAll(btnEdit, btnRemove)
            } else {
                val btnDeclare = Button("声明").apply {
                    style = "-fx-font-size: 11px; -fx-cursor: hand; -fx-background-color: #27ae60; -fx-text-fill: white; -fx-padding: 2 8;"
                    tooltip = Tooltip("激活并配置该用途的策略声明")
                    setOnAction { openEditor(draft.tagId) }
                }
                children.add(btnDeclare)
            }
        }

        grid.add(purposeLabel, 0, gridRow)
        grid.add(treeLabel, 1, gridRow)
        grid.add(timingLabel, 2, gridRow)
        grid.add(surplusLabel, 3, gridRow)
        grid.add(actionBox, 4, gridRow)
    }

    private fun dimensionStyle(declared: Boolean): String =
        if (declared) "-fx-font-size: 11px; -fx-text-fill: #2c3e50; -fx-cursor: hand;"
        else "-fx-font-size: 11px; -fx-text-fill: #95a5a6; -fx-cursor: hand;"

    /** 弹窗编辑返回后回写。 */
    private fun openEditor(tagId: String) {
        val index = drafts.indexOfFirst { it.tagId == tagId }
        if (index < 0) return
        val displayName = currentTagDisplayNames[tagId] ?: tagId
        val dialog = PresetPurposeEditDialog(drafts[index], candidateTreesByTag[tagId].orEmpty(), displayName)
        val edited = dialog.showAndWait().orElse(null) ?: return
        drafts[index] = edited
        renderRows()
        notifyDeclarations()
    }

    private fun notifyDeclarations() {
        onTreeDeclarationsChanged?.invoke(collectTreeSelections().keys)
        onDeclarationsChanged?.invoke()
    }

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
        val replan = if (draft.timing.defaultReplanAfterUse == true) "重规划" else "不重规划"
        val priority = draft.timing.priority?.toString() ?: "默认"
        "$stage / 权重$weight / $replan / P$priority"
    }

    private fun surplusSummary(draft: PurposeDeclarationDraft): String = when {
        !draft.declaredSurplus -> "— (未声明)"
        draft.surplusThreshold == null -> "声明为「不设门槛」"
        else -> "门槛 ${draft.surplusThreshold}"
    }
}
