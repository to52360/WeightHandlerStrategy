package lin.ui.strategy_preset

import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.PresetDetail
import lin.repository.card_group.SurplusOverride
import lin.repository.card_group.TimingOverride
import lin.ui.service.AuraBoostOption

/**
 * 策略预设全景配置独立弹窗（T-TG-037 空间重构）。
 *
 * 解决右侧面板宽度狭窄（500~600px）无法舒适容纳 5 列大表的根本痛点。
 * 规格：880×620px 宽敞视窗，可自适应拉伸。
 *
 * 核心特性：
 * 1. 默认仅看已声明用途（消灭 80% 无效未配置死行）；
 * 2. 顶部提供「➕ 添加用途声明」下拉框，一秒选定未配置用途并即刻激活编辑；
 * 3. 顶部支持视图过滤（已配置 / 未配置候选 / 全部用途）；
 * 4. 宽屏承载 [PresetPurposeTable] 与全局光环白名单声明；
 * 5. 保存时带空预设二次防呆确认。
 */
class StrategyPresetConfigDialog(
    private val presetDetail: PresetDetail,
    private val state: StrategyPresetState,
    private val onSave: (
        treeSelections: Map<String, Collection<String>>,
        timings: Map<String, TimingOverride>,
        surplus: Map<String, SurplusOverride>,
        auraSelection: Set<String>
    ) -> Unit
) : Dialog<Unit>() {

    private val purposeTable = PresetPurposeTable()

    // ── 顶部工具栏控件 ──
    private val comboAddPurpose = ComboBox<TagOptionItem>().apply {
        promptText = "+ 添加用途声明..."
        prefWidth = 200.0
    }

    private val toggleGroup = ToggleGroup()
    private val rbDeclared = RadioButton().apply {
        toggleGroup = this@StrategyPresetConfigDialog.toggleGroup
        isSelected = true
        style = "-fx-font-weight: bold; -fx-text-fill: #27ae60;"
    }
    private val rbUndeclared = RadioButton().apply {
        toggleGroup = this@StrategyPresetConfigDialog.toggleGroup
        style = "-fx-text-fill: #7f8c8d;"
    }
    private val rbAll = RadioButton().apply {
        toggleGroup = this@StrategyPresetConfigDialog.toggleGroup
        style = "-fx-text-fill: #34495e;"
    }

    // ── 全局光环声明区 ──
    private val auraCheckBoxes = linkedMapOf<String, CheckBox>()
    private val auraSection = VBox(6.0).apply {
        style = "-fx-border-color: #bdc3c7; -fx-border-width: 1px; -fx-border-radius: 4px; -fx-padding: 8px; -fx-background-color: #fcfcfc;"
    }

    init {
        val preset = presetDetail.preset
        title = "策略预设全景配置 - [${preset.name}]"
        headerText = "为预设 [${preset.name}] 配置战略用途（树白名单、出牌时序、惜售门槛）与全局光环白名单。\n" +
                "• 声明粒度为维度级（勾 = 声明具体值并落库，不勾 = 未声明）。\n" +
                "• 未声明树白名单的用途，其对应的全局用途树将被全部关闭兜底。"

        val dialogPane = this.dialogPane
        dialogPane.prefWidth = 880.0
        dialogPane.prefHeight = 620.0
        isResizable = true
        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

        val okBtn = dialogPane.lookupButton(ButtonType.OK) as? Button
        okBtn?.text = "保存策略配置"
        okBtn?.style = "-fx-font-weight: bold; -fx-background-color: #2980b9; -fx-text-fill: white; -fx-cursor: hand;"

        setupTableAndAura()
        setupToolBar()
        dialogPane.content = buildContent()
        installSaveGate(okBtn)
    }

    private fun setupTableAndAura() {
        purposeTable.load(
            rules = state.timingRules,
            candidateTrees = state.candidateTrees,
            treeSelections = presetDetail.treeSelections,
            timings = presetDetail.timings,
            surplus = presetDetail.surplus,
            tagDisplayNames = state.tagDisplayNames
        )

        // 监听表格声明变动，刷新候选下拉框与单选标签计数
        purposeTable.onDeclarationsChanged = {
            refreshAddComboAndBadges()
        }

        buildAuraSection(state.candidateAuraBoosts, presetDetail.auraSelection)
        refreshAddComboAndBadges()
    }

    private fun setupToolBar() {
        // 下拉选择未声明用途时，自动激活编辑
        comboAddPurpose.valueProperty().addListener { _, _, selected ->
            if (selected != null) {
                val tagId = selected.tagId
                javafx.application.Platform.runLater {
                    comboAddPurpose.value = null
                    purposeTable.openEditorForTag(tagId)
                }
            }
        }

        // 过滤切换
        rbDeclared.setOnAction {
            purposeTable.filterMode = PurposeFilterMode.DECLARED_ONLY
        }
        rbUndeclared.setOnAction {
            purposeTable.filterMode = PurposeFilterMode.UNDECLARED_ONLY
        }
        rbAll.setOnAction {
            purposeTable.filterMode = PurposeFilterMode.ALL
        }
    }

    private fun refreshAddComboAndBadges() {
        val undeclared = purposeTable.getUndeclaredTagOptions()
        val declared = purposeTable.getDeclaredTagOptions()
        val total = undeclared.size + declared.size

        rbDeclared.text = "已配置 (${declared.size})"
        rbUndeclared.text = "未配置候选 (${undeclared.size})"
        rbAll.text = "全部 ($total)"

        comboAddPurpose.items.setAll(undeclared.map { TagOptionItem(it.first, "${it.second} (${it.first})") })
        comboAddPurpose.isDisable = undeclared.isEmpty()
    }

    private fun buildAuraSection(candidates: List<AuraBoostOption>, selected: Set<String>) {
        auraSection.children.clear()
        auraCheckBoxes.clear()

        val title = Label("📡 全局光环白名单（勾选 = 本预设保留该全局光环；卡组私有光环不受此限制）").apply {
            style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #2c3e50;"
        }
        auraSection.children.add(title)

        if (candidates.isEmpty()) {
            val emptyHint = Label("库中没有全局光环规则（manager_id 为空）。卡组私有光环归属即拥有，不需要在此声明。").apply {
                style = "-fx-font-size: 11px; -fx-text-fill: #95a5a6;"
            }
            auraSection.children.add(emptyHint)
            return
        }

        val flow = FlowPane(12.0, 6.0).apply {
            padding = Insets(4.0, 0.0, 4.0, 0.0)
        }
        candidates.forEach { option ->
            val cb = CheckBox("${option.name} (${option.id})").apply {
                isSelected = option.id in selected
                style = "-fx-font-size: 11px;"
            }
            auraCheckBoxes[option.id] = cb
            flow.children.add(cb)
        }
        auraSection.children.add(flow)
    }

    private fun buildContent(): VBox {
        val toolBar = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(4.0, 0.0, 6.0, 0.0)
            children.addAll(
                Label("视图:").apply { style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #34495e;" },
                rbDeclared,
                rbUndeclared,
                rbAll,
                HBox().apply { HBox.setHgrow(this, Priority.ALWAYS) },
                comboAddPurpose
            )
        }

        VBox.setVgrow(purposeTable, Priority.ALWAYS)

        val scrollWrapper = ScrollPane(VBox(10.0).apply {
            padding = Insets(4.0)
            children.addAll(toolBar, purposeTable, auraSection)
        }).apply {
            isFitToWidth = true
            isFitToHeight = true
            style = "-fx-background-color: transparent;"
        }
        VBox.setVgrow(scrollWrapper, Priority.ALWAYS)

        return VBox(8.0).apply {
            padding = Insets(10.0)
            children.add(scrollWrapper)
        }
    }

    private fun installSaveGate(okBtn: Button?) {
        okBtn?.addEventFilter(ActionEvent.ACTION) { event ->
            val treeSelections = purposeTable.collectTreeSelections()
            val timings = purposeTable.collectTimings()
            val surplus = purposeTable.collectSurplus()
            val auraSelection = auraCheckBoxes.filterValues { it.isSelected }.keys.toSet()

            // Q8 防呆二次确认：没有任何用途声明树维度
            if (treeSelections.isEmpty()) {
                val confirmEmpty = Alert(Alert.AlertType.CONFIRMATION).apply {
                    title = "空预设保存二次确认"
                    headerText = "当前预设没有任何用途声明「树白名单」"
                    contentText = "⚠️ 注意：保存后将关闭引用该预设的卡组的全部全局用途树兜底策略！\n" +
                            "（空预设是合法的纯私有策略表达，但后果较大）。\n\n确定要保存为空预设吗？"
                }.showAndWait()
                if (!confirmEmpty.isPresent || confirmEmpty.get() != ButtonType.OK) {
                    event.consume()
                    return@addEventFilter
                }
            }

            onSave(treeSelections, timings, surplus, auraSelection)
        }
    }

    private data class TagOptionItem(val tagId: String, val text: String) {
        override fun toString() = text
    }
}
