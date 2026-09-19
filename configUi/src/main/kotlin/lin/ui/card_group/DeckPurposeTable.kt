package lin.ui.card_group

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
import lin.repository.card_group.Dimension
import lin.repository.card_group.SurplusOverride
import lin.repository.card_group.ThresholdPatch
import lin.repository.card_group.TimingOverride
import lin.repository.card_purpose.PurposeTagRuleEntity
import lin.repository.tree_config.TreeConfigEntity

/**
 * 卡组增量项里**单个用途**的编辑草稿（消费侧 = 覆盖语义，D-TG-019 / D-TG-020）。
 *
 * - [used] = false ⇒ **该用途整体退出本卡组**（规则被减掉 + 其用途树停用，见 D-TG-020）；
 * - [excludedTrees] = 在预设白名单之上"本卡组额外排除"的树（**树级细粒度**）；
 * - [overrideTiming] / [overrideSurplus] = 该维度是否**覆盖**（不覆盖 = 继承预设/默认）。
 */
data class DeckPurposeDraft(
    val rule: PurposeTagRuleEntity,
    val used: Boolean = true,
    val excludedTrees: Set<String> = emptySet(),
    val overrideTiming: Boolean = false,
    val timing: TimingOverride = TimingOverride(),
    val overrideSurplus: Boolean = false,
    /** 仅 [overrideSurplus] 为真时有意义；null = 覆盖为「不设门槛」。 */
    val surplusThreshold: Int? = null,
    /**
     * **维度级被禁维度**（D-TG-021，部分禁透传）：该用途被禁的维度子集。
     *
     * - `used = true` 时，非空 = MCP 通过 `excludeScopes` 写入的**部分禁**，本 UI 原样透传（不编辑、不丢档）；
     * - `used = false` 时本字段忽略（视为全禁，走 collectExcludedPurposes）。
     */
    val excludedDimensions: Set<String> = emptySet()
) {
    val tagId: String get() = rule.tagId
}


/**
 * 卡组增量项「**用途 × 微调**」聚合表（消费侧，L1 布局）。
 *
 * 为什么改成按用途组织：原实现按**维度**分三个页签（排除树 / 时序 / 惜售），
 * 于是"这个用途到底怎么调"要跨页签拼；而且"去除"被藏在树页签里，看起来像"按用途去标签"。
 * 现改为 **一行一个用途**：
 * - 行首 `☑ 使用该用途`：**不勾 = 整个用途退出本卡组**（这是唯一的"去除"入口，用途级）；
 * - 三个维度各自 `☑ 覆盖 + 摘要`（不勾 = 继承预设 / 默认值）；
 * - 细节进 [DeckPurposeEditDialog]，不切页签。
 *
 * ⚠️ 与预设侧的 [lin.ui.strategy_preset.PresetPurposeTable] **形态相似但语义不同**：
 * 预设侧勾 = **声明**（落具体值、无继承），消费侧勾 = **覆盖**（不勾即继承）。
 */
class DeckPurposeTable : VBox(6.0) {

    private val grid = GridPane().apply {
        hgap = 10.0
        vgap = 4.0
        padding = Insets(6.0)
        columnConstraints.addAll(
            ColumnConstraints(55.0),                    // 使用
            ColumnConstraints(100.0),                   // 用途
            ColumnConstraints(130.0),                   // 树维度
            ColumnConstraints(180.0),                   // 时序维度
            ColumnConstraints(120.0),                   // 惜售维度
            ColumnConstraints(55.0)                     // 编辑
        )
    }

    private val scrollPane = ScrollPane(grid).apply {
        isFitToWidth = true
        prefHeight = 300.0
        style = "-fx-background-color: transparent;"
    }

    private val emptyLabel = Label("当前数据库中无可声明的内置作用").apply {
        style = "-fx-text-fill: #95a5a6; -fx-padding: 10px;"
        isVisible = false
        isManaged = false
    }

    private val drafts = mutableListOf<DeckPurposeDraft>()
    private val rows = mutableMapOf<String, RowControls>()
    private var treesByTag: Map<String, List<TreeConfigEntity>> = emptyMap()
    private var currentTagDisplayNames: Map<String, String> = emptyMap()

    init {
        padding = Insets(6.0)
        VBox.setVgrow(scrollPane, Priority.ALWAYS)
        children.addAll(scrollPane, emptyLabel)
    }

    /**
     * 装载可调用途与当前增量项。
     *
     * @param rules 可声明的内置作用清单（含默认值提示）
     * @param candidateTrees 全局共享用途树候选
     * @param excludedPurposes 本卡组**整用途不使用**的用途（全禁）
     * @param exclusionDimensions **维度级排除**完整映射（D-TG-021）：用途 → 被禁维度子集（含全禁；供部分禁透传）
     * @param treeExclusions 本卡组额外排除的树（用途 → 树 id）
     * @param timings 时序覆盖项
     * @param surplus 惜售覆盖项
     * @param tagDisplayNames 标签中文显示名映射（数据源自 purpose_tag_def 表）
     */
    fun load(
        rules: List<PurposeTagRuleEntity>,
        candidateTrees: List<TreeConfigEntity>,
        excludedPurposes: Set<String>,
        exclusionDimensions: Map<String, Set<String>>,
        treeExclusions: Map<String, Set<String>>,
        timings: Map<String, TimingOverride>,
        surplus: Map<String, SurplusOverride>,
        tagDisplayNames: Map<String, String> = emptyMap()
    ) {
        drafts.clear()
        rows.clear()
        grid.children.clear()
        currentTagDisplayNames = tagDisplayNames
        treesByTag = candidateTrees
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
            val timing = timings[rule.tagId]
            val surplusItem = surplus[rule.tagId]
            val fullExcluded = rule.tagId in excludedPurposes
            // 部分禁：库里「被禁维度」不等于全集，且该用途未被全禁 ⇒ 透传，供随后保存原样写回（不丢档）。
            val partialExcluded = if (fullExcluded) emptySet() else exemptionDimensionsValue(rule.tagId, exclusionDimensions)
            val draft = DeckPurposeDraft(
                rule = rule,
                used = !fullExcluded,
                excludedTrees = treeExclusions[rule.tagId].orEmpty(),
                overrideTiming = timing != null,
                timing = timing ?: TimingOverride(),
                overrideSurplus = surplusItem != null,
                surplusThreshold = surplusItem?.surplusIdleThreshold?.value,
                excludedDimensions = partialExcluded
            )
            drafts += draft
            addDataRow(index + 1, draft)
        }
    }

    /** 取某用途的部分禁维度子集 —— 忽略「全禁」（全集由 [excludedPurposes] 表达，避免与 used=false 冲突）。 */
    private fun exemptionDimensionsValue(tag: String, exclusionDimensions: Map<String, Set<String>>): Set<String> {
        val dims = exclusionDimensions[tag].orEmpty()
        // 只有「非全集」的才是部分禁；全集（整用途退出）不在这里表达语义
        return if (dims == Dimension.EXCLUDABLE_DIMENSIONS) emptySet() else dims
    }

    // ── 收集（整体替换语义）──

    /**
     * **维度级排除**完整映射（D-TG-021）：用途 → 被禁维度集合。
     * 合并两个来源：勾选「不使用」（[used]=false）的用途 = 全禁（全集）；
     * 勾「使用」但库里有**部分禁**状态（`excludeScopes` 写入、本 UI 透传）的用途 = 其部分禁子集。
     * 语义由服务层 `saveDeckDelta` 落地（全禁 ∪ 部分禁合成一张表）。
     */
    fun collectExclusionDimensions(): Map<String, Set<String>> {
        val result = mutableMapOf<String, Set<String>>()
        drafts.forEach { d ->
            if (!d.used) {
                result[d.tagId] = Dimension.EXCLUDABLE_DIMENSIONS
            } else if (d.excludedDimensions.isNotEmpty()) {
                result[d.tagId] = d.excludedDimensions
            }
        }
        return result
    }

    /** @deprecated 由 [collectExclusionDimensions] 取代（其全禁项即全集被禁）。兼容旧调用保留导出。 */
    @Deprecated("用 collectExclusionDimensions() 取代；全禁 = 全集被禁")
    fun collectExcludedPurposes(): Set<String> =
        drafts.filterNot { it.used }.map { it.tagId }.toSet()

    /**
     * 本卡组额外排除的树（树级）。
     *
     * ⚠️ D-TG-021 议题④(b)：**不再带 `used &&` 前缀** —— 勾掉「使用」只把用途降级为全禁，
     * 其覆盖与树排除**保留休眠行**（随排除一并写出，取消排除后原样复活），不再被静默清空。
     */
    fun collectTreeExclusions(): Map<String, Set<String>> =
        drafts.filter { it.excludedTrees.isNotEmpty() }
            .associate { it.tagId to it.excludedTrees }

    /** 时序覆盖项（④(b)：不勾「使用」也保留休眠覆盖行）。 */
    fun collectTimings(): Map<String, TimingOverride> =
        drafts.filter { it.overrideTiming }.associate { it.tagId to it.timing }

    /** 惜售覆盖项（④(b)：不勾「使用」也保留休眠覆盖行）。 */
    fun collectSurplus(): Map<String, SurplusOverride> =
        drafts.filter { it.overrideSurplus }
            .associate { it.tagId to SurplusOverride(ThresholdPatch(it.surplusThreshold)) }

    // ── 行装配 ──

    private fun addHeaderRow() {
        listOf("使用", "用途", "树维度", "时序维度", "惜售维度", "操作").forEachIndexed { col, text ->
            grid.add(
                Label(text).apply {
                    style = "-fx-font-weight: bold; -fx-text-fill: #34495e; -fx-font-size: 11px;"
                },
                col,
                0
            )
        }
    }

    private fun addDataRow(gridRow: Int, draft: DeckPurposeDraft) {
        val chkUsed = CheckBox().apply {
            isSelected = draft.used
            tooltip = javafx.scene.control.Tooltip(
                "不勾 = 本卡组不使用该用途：不再产生出牌规则（阶段回落到 GENERAL）、其用途树也不再参与打分。\n" +
                        "（内部：该用途从本卡组的规则集合里被减掉，不会回落到全局默认值）"
            )
            selectedProperty().addListener { _, _, newValue -> updateDraft(draft.tagId) { it.copy(used = newValue) } }
        }

        val displayName = currentTagDisplayNames[draft.tagId] ?: draft.tagId
        val lblTag = Label(displayName).apply {
            style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand;"
            tooltip = javafx.scene.control.Tooltip(draft.tagId)
            setOnMouseClicked { event -> if (event.clickCount == 2) openEditor(draft.tagId) }
        }

        val lblTree = Label(treeSummary(draft)).apply {
            style = treeStyle(draft)
            tooltip = javafx.scene.control.Tooltip("双击可编辑该用途微调")
            setOnMouseClicked { event -> if (event.clickCount == 2) openEditor(draft.tagId) }
        }
        val lblTiming = Label(timingSummary(draft)).apply {
            style = timingStyle(draft)
            tooltip = javafx.scene.control.Tooltip("双击可编辑该用途微调")
            setOnMouseClicked { event -> if (event.clickCount == 2) openEditor(draft.tagId) }
        }
        val lblSurplus = Label(surplusSummary(draft)).apply {
            style = surplusStyle(draft)
            tooltip = javafx.scene.control.Tooltip("双击可编辑该用途微调")
            setOnMouseClicked { event -> if (event.clickCount == 2) openEditor(draft.tagId) }
        }

        val btnEdit = Button("编辑").apply {
            style = "-fx-font-size: 11px; -fx-cursor: hand; -fx-padding: 2 8;"
            tooltip = javafx.scene.control.Tooltip("编辑该用途微调")
        }
        btnEdit.setOnAction { openEditor(draft.tagId) }

        rows[draft.tagId] = RowControls(chkUsed, lblTag, lblTree, lblTiming, lblSurplus, btnEdit)

        grid.add(chkUsed, 0, gridRow)
        grid.add(lblTag, 1, gridRow)
        grid.add(lblTree, 2, gridRow)
        grid.add(lblTiming, 3, gridRow)
        grid.add(lblSurplus, 4, gridRow)
        grid.add(btnEdit, 5, gridRow)

        applyUsedState(draft)
    }

    private fun updateDraft(tagId: String, transform: (DeckPurposeDraft) -> DeckPurposeDraft) {
        val index = drafts.indexOfFirst { it.tagId == tagId }
        if (index < 0) return
        drafts[index] = transform(drafts[index])
        syncRow(drafts[index])
    }

    private fun syncRow(draft: DeckPurposeDraft) {
        val row = rows[draft.tagId] ?: return
        row.lblTag.text = currentTagDisplayNames[draft.tagId] ?: draft.tagId
        row.lblTree.text = treeSummary(draft)
        row.lblTree.style = treeStyle(draft)
        row.lblTiming.text = timingSummary(draft)
        row.lblTiming.style = timingStyle(draft)
        row.lblSurplus.text = surplusSummary(draft)
        row.lblSurplus.style = surplusStyle(draft)
        applyUsedState(draft)
    }

    /** 不使用该用途时，三个维度的覆盖控件与编辑按钮一并禁用（排除优先于覆盖）。 */
    private fun applyUsedState(draft: DeckPurposeDraft) {
        val row = rows[draft.tagId] ?: return
        row.editButton.isDisable = !draft.used
        val dim = if (draft.used) 1.0 else 0.4
        listOf(row.lblTag, row.lblTree, row.lblTiming, row.lblSurplus).forEach { it.opacity = dim }
    }

    private fun openEditor(tagId: String) {
        val index = drafts.indexOfFirst { it.tagId == tagId }
        if (index < 0) return
        val displayName = currentTagDisplayNames[tagId] ?: tagId
        val dialog = DeckPurposeEditDialog(drafts[index], treesByTag[tagId].orEmpty(), displayName)
        val edited = dialog.showAndWait().orElse(null) ?: return
        drafts[index] = edited
        syncRow(edited)
    }

    // ── 摘要文案与样式 ──

    private fun dimensionStyle(isDisabled: Boolean, isOverridden: Boolean): String = when {
        isDisabled -> "-fx-font-size: 11px; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
        isOverridden -> "-fx-font-size: 11px; -fx-text-fill: #2980b9; -fx-font-weight: bold;"
        else -> "-fx-font-size: 11px; -fx-text-fill: #7f8c8d;"
    }

    private fun treeStyle(draft: DeckPurposeDraft): String =
        dimensionStyle(Dimension.PURPOSE_TREE in draft.excludedDimensions, draft.excludedTrees.isNotEmpty())

    private fun timingStyle(draft: DeckPurposeDraft): String =
        dimensionStyle(Dimension.PURPOSE_TIMING in draft.excludedDimensions, draft.overrideTiming)

    private fun surplusStyle(draft: DeckPurposeDraft): String =
        dimensionStyle(Dimension.PURPOSE_SURPLUS in draft.excludedDimensions, draft.overrideSurplus)

    private fun treeSummary(draft: DeckPurposeDraft): String = when {
        Dimension.PURPOSE_TREE in draft.excludedDimensions -> "🚫 禁用维度"
        draft.excludedTrees.isEmpty() -> "— (继承)"
        else -> "额外排除 ${draft.excludedTrees.size} 棵"
    }

    private fun timingSummary(draft: DeckPurposeDraft): String = when {
        Dimension.PURPOSE_TIMING in draft.excludedDimensions -> "🚫 禁用维度"
        !draft.overrideTiming -> "— (继承)"
        else -> {
            val stage = draft.timing.defaultStage ?: "继承"
            val weight = draft.timing.defaultOrderWeight?.toString() ?: "继承"
            val replan = when (draft.timing.defaultReplanAfterUse) {
                null -> "继承"
                true -> "是"
                false -> "否"
            }
            "$stage / 权重$weight / 重规划$replan"
        }
    }

    private fun surplusSummary(draft: DeckPurposeDraft): String = when {
        Dimension.PURPOSE_SURPLUS in draft.excludedDimensions -> "🚫 禁用维度"
        !draft.overrideSurplus -> "— (继承)"
        draft.surplusThreshold == null -> "覆盖为「不设门槛」"
        else -> "覆盖为 ${draft.surplusThreshold}"
    }

    private class RowControls(
        val chkUsed: CheckBox,
        val lblTag: Label,
        val lblTree: Label,
        val lblTiming: Label,
        val lblSurplus: Label,
        val editButton: Button
    )
}
