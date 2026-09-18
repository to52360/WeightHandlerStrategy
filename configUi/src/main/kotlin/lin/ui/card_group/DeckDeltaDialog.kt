package lin.ui.card_group

import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.AuraDelta

/**
 * 卡组增量项（微调层）编辑弹窗（T-TG-018 优化；2026-09-16 改为**按用途聚合的单页表**）。
 *
 * 对应三层模型微调层：预设 > 卡组增量项 > 全局规则。
 *
 * ⚠️ **不再按维度分页签**（原「排除树 / 时序覆盖 / 惜售覆盖」三个页签）——
 * "这个用途到底怎么调"要跨页签拼，而且"去除"藏在树页签里、看起来像按用途去标签。
 * 现改为一行一个用途（[DeckPurposeTable]）：行首「使用」是**唯一的去除入口**（用途级，D-TG-020），
 * 三个维度各自「覆盖 / 继承」，细节进 [DeckPurposeEditDialog]（不切页签）。
 */
class DeckDeltaDialog(
    private val store: WorkbenchStore
) : Dialog<Unit>() {

    private val purposeTable = DeckPurposeTable()

    // ── D-DP-002「光环增量」区（三档：增 / 减 / 覆盖分值；光环不按用途分组）──
    private val auraSection = VBox(4.0).apply {
        style = "-fx-border-color: #bdc3c7; -fx-border-width: 1px; -fx-border-radius: 4px; -fx-padding: 6px;"
    }
    /** id → (增, 减) 勾选框（每次刷新按候选重建）。 */
    private val auraToggles = linkedMapOf<String, Pair<CheckBox, CheckBox>>()
    /** id → 分值覆盖输入（留空 = 不覆盖）。 */
    private val auraScoreFields = linkedMapOf<String, TextField>()

    private val errorLabel = Label().apply {
        style = "-fx-font-size: 11px; -fx-text-fill: #c0392b; -fx-font-weight: bold;"
        isVisible = false
        isManaged = false
    }

    private val resetBtn = Button("🔄 恢复为已保存状态").apply {
        style = "-fx-font-size: 11px; -fx-cursor: hand;"
    }

    init {
        val currentItem = store.state.selectedManagerItem
        val managerName = currentItem?.entity?.name?.ifBlank { "未命名方案" } ?: "未命名方案"
        title = "⚙️ 卡组策略微调 (Delta) - [$managerName]"
        headerText = "卡组增量项（解析链：预设 > 卡组增量项 > 全局规则）。\n" +
                "「使用」不勾 = 该用途整体退出本卡组；点击 ✏️ 或双击行可对「树 / 时序 / 惜售」进行维度级控制（继承 / 覆盖 / 禁用维度）。"

        val dialogPane = this.dialogPane
        dialogPane.prefWidth = 820.0
        dialogPane.prefHeight = 520.0
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val okBtn = dialogPane.lookupButton(ButtonType.OK) as? Button
        okBtn?.text = "💾 保存微调"
        okBtn?.style = "-fx-font-weight: bold; -fx-background-color: #27ae60; -fx-text-fill: white; -fx-cursor: hand;"

        // 载入数据（初始加载 + 重置按钮复用）
        fun reloadData() {
            val delta = store.state.currentDeckDelta
            purposeTable.load(
                rules = store.state.timingRules,
                candidateTrees = store.state.candidateTrees,
                excludedPurposes = delta?.excludedPurposes ?: emptySet(),
                exclusionDimensions = delta?.exclusionDimensions ?: emptyMap(),
                treeExclusions = delta?.treeExclusions ?: emptyMap(),
                timings = delta?.timings ?: emptyMap(),
                surplus = delta?.surplus ?: emptyMap(),
                tagDisplayNames = store.state.tagDisplayNames
            )
            refreshAura()
            errorLabel.isVisible = false
            errorLabel.isManaged = false
        }

        reloadData()
        resetBtn.setOnAction { reloadData() }
        dialogPane.content = buildContent()
        installSaveGate(okBtn)
    }

    /** 弹窗内容：按用途聚合的微调表 + 底部工具条。 */
    private fun buildContent(): VBox {
        VBox.setVgrow(purposeTable, Priority.ALWAYS)

        val toolBar = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 0.0, 0.0, 0.0)
            children.addAll(resetBtn, errorLabel)
        }

        return VBox(8.0).apply {
            padding = Insets(10.0)
            children.addAll(
                Label("🚫 不勾「使用」= 本卡组不使用该用途：它不再影响本卡组的出牌编排，其用途树也不再参与打分。")
                    .apply { style = "-fx-font-size: 11px; -fx-text-fill: #7f8c8d;" },
                purposeTable,
                auraSection,
                toolBar
            )
        }
    }

    /**
     * 提交校验与保存拦截（**覆盖语义**：留空 = 继承预设/默认，故字段级三态是必需的）。
     * 数值合法性已在 [DeckPurposeEditDialog] 应用前拦截 ⇒ 此处只做收集与保存。
     *
     * ⚠️ 排除通道走 [DeckPurposeTable.collectExclusionDimensions]（全禁 ∪ 部分禁合成），
     * 不再单独走被弃用的 `collectExcludedPurposes`。
     */
    private fun installSaveGate(okBtn: Button?) {
        okBtn?.addEventFilter(ActionEvent.ACTION) { event ->
            val saveError = store.saveDeckDelta(
                treeExclusions = purposeTable.collectTreeExclusions(),
                timings = purposeTable.collectTimings(),
                surplus = purposeTable.collectSurplus(),
                exclusionDimensions = purposeTable.collectExclusionDimensions(),
                auraDelta = collectAuraDelta()
            )
            if (saveError != null) {
                flagError("保存失败: $saveError", event)
            }
        }
    }

    /**
     * 刷新「光环增量」区（D-DP-002）：候选恒为**全局行**；每行「增 / 减」互斥 + 可选分值覆盖。
     *
     * 三档语义（[AuraDelta]）：生效集 = 预设白名单 ∪ extra − exclude；分值 = 覆盖值 ?: 原值。
     * ⚠️ 与用途维度不同：光环**不按用途分组**，故不放进 [DeckPurposeTable]，单列一区。
     */
    private fun refreshAura() {
        auraSection.children.clear()
        auraToggles.clear()
        auraScoreFields.clear()

        auraSection.children.add(
            Label("📡 光环增量（全局光环：白名单之上再增 / 再减 / 覆盖分值）")
                .apply { style = "-fx-font-weight: bold; -fx-font-size: 11px;" }
        )

        val candidates = store.state.candidateAuraBoosts
        val delta = store.state.currentDeckDelta?.auraDelta ?: AuraDelta.NONE
        if (candidates.isEmpty()) {
            auraSection.children.add(
                Label("库中没有全局光环行（manager_id 为空）。").apply {
                    style = "-fx-font-size: 11px; -fx-text-fill: #95a5a6;"
                }
            )
            return
        }
        candidates.forEach { option ->
            val extra = CheckBox("增")
            val exclude = CheckBox("减")
            extra.isSelected = option.id in delta.extra
            exclude.isSelected = option.id in delta.exclude
            // 同一行不能既加又减（服务端也会拒；这里在 UI 层就互斥）
            extra.setOnAction { if (extra.isSelected) exclude.isSelected = false }
            exclude.setOnAction { if (exclude.isSelected) extra.isSelected = false }

            val score = TextField().apply {
                promptText = "覆盖分值(费)"
                prefWidth = 100.0
                delta.scoreOverrides[option.id]?.let { text = it.toString() }
            }
            auraToggles[option.id] = extra to exclude
            auraScoreFields[option.id] = score

            auraSection.children.add(
                HBox(6.0).apply {
                    alignment = Pos.CENTER_LEFT
                    children.addAll(
                        Label("${option.name}（${option.id}）").apply { prefWidth = 240.0 },
                        extra,
                        exclude,
                        score
                    )
                }
            )
        }
    }

    /** 收集光环增量三档（未勾未填 = 该行不声明）。 */
    private fun collectAuraDelta(): AuraDelta {
        val extra = auraToggles.filterValues { it.first.isSelected }.keys.toSet()
        val exclude = auraToggles.filterValues { it.second.isSelected }.keys.toSet()
        val overrides = auraScoreFields.mapNotNull { (id, field) ->
            field.text.trim().toDoubleOrNull()?.let { id to it }
        }.toMap()
        return AuraDelta(extra = extra, exclude = exclude, scoreOverrides = overrides)
    }

    private fun flagError(message: String, event: ActionEvent) {
        errorLabel.text = "❌ $message"
        errorLabel.isVisible = true
        errorLabel.isManaged = true
        event.consume()
    }
}
