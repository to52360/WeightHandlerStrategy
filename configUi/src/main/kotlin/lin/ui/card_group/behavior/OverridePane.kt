package lin.ui.card_group.behavior

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.bean.usePlan.ConditionalStageOverride
import lin.bean.usePlan.UseStage
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.rule.tree.findOverride
import lin.ui.card_group.WorkbenchStore
import lin.ui.condition_tree.ConditionTreeDialog
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 条件树下拉项数据封装
 */
data class ConditionTreeOption(
    val id: String,
    val name: String
) {
    override fun toString(): String {
        return if (id.isEmpty()) "(未选择条件树)" else "[$id] $name"
    }
}

/**
 * OVERRIDE 类型行为编辑面板：阶段覆盖 + 重规划 + 排序权重 + 条件化阶段（动态排序）。
 * 分组卡片化布局（基础覆盖 + 动态条件），支持从当前上下文直接新建/编辑/预览条件树。
 */
class OverridePane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) : KoinComponent {

    val node: VBox

    private val conditionTreeRepository: ConditionTreeConfigRepository by inject()

    // ── 基础覆盖控件 ──
    private val stageCombo = ComboBox<String>().apply {
        items.setAll(BehaviorDisplayMappers.allStageLabels())
        promptText = "出牌阶段覆盖"
        disableProperty().bind(disableWhen)
    }
    private val replanCombo = ComboBox<String>().apply {
        items.setAll("(不覆盖)", "是", "否")
        promptText = "出牌后重规划"
        disableProperty().bind(disableWhen)
    }
    private val weightField = TextField().apply {
        promptText = "排序权重"
        prefWidth = 80.0
        disableProperty().bind(disableWhen)
    }

    // ── 动态条件阶段控件 ──
    private val enableCSCheckBox = CheckBox("启用条件阶段控制 (Conditional Stage)").apply {
        style = "-fx-font-weight: bold; -fx-text-fill: #343a40;"
        disableProperty().bind(disableWhen)
    }

    private val conditionTreeCombo = ComboBox<ConditionTreeOption>().apply {
        promptText = "选择条件树..."
        prefWidth = 240.0
        disableProperty().bind(disableWhen)
    }

    private val newTreeBtn = Button("➕ 新建").apply {
        style = "-fx-background-color: #198754; -fx-text-fill: white; -fx-font-size: 11px;"
        disableProperty().bind(disableWhen)
    }
    private val editTreeBtn = Button("✏️ 编辑").apply {
        style = "-fx-background-color: #0d6efd; -fx-text-fill: white; -fx-font-size: 11px;"
        disableProperty().bind(disableWhen)
    }
    private val previewTreeBtn = Button("👁 预览").apply {
        style = "-fx-background-color: #6c757d; -fx-text-fill: white; -fx-font-size: 11px;"
        disableProperty().bind(disableWhen)
    }

    private val conditionStageCombo = ComboBox<String>().apply {
        items.setAll(BehaviorDisplayMappers.allStageLabels())
        promptText = "命中阶段"
        disableProperty().bind(disableWhen)
    }
    private val conditionElseCombo = ComboBox<String>().apply {
        items.setAll(BehaviorDisplayMappers.allStageLabels())
        promptText = "未命中阶段(可选)"
        disableProperty().bind(disableWhen)
    }

    private var isUpdatingFromState = false

    init {
        val baseBlock = buildBaseBlock()
        val conditionalBlock = buildConditionalBlock(disableWhen)

        node = VBox(10.0).apply {
            children.addAll(baseBlock, conditionalBlock)
        }

        refreshConditionTreeOptions()
        setupButtonActions()
        setupControlListeners()
        setupStateObserver()
    }

    private fun buildBaseBlock(): HBox {
        return HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            padding = Insets(8.0)
            style =
                "-fx-background-color: #f8f9fa; -fx-border-color: #e9ecef; -fx-border-radius: 4; -fx-background-radius: 4;"
            children.addAll(
                Label("阶段覆盖:"), stageCombo,
                Label("重规划:"), replanCombo,
                Label("排序权重:"), weightField
            )
        }
    }

    private fun buildConditionalBlock(disableWhen: ObservableBooleanValue): VBox {
        val treeSelectRow = HBox(10.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("条件树:"),
                conditionTreeCombo,
                newTreeBtn,
                editTreeBtn,
                previewTreeBtn
            )
        }

        val stageSelectRow = HBox(15.0).apply {
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("命中阶段:"), conditionStageCombo,
                Label("未命中阶段:"), conditionElseCombo
            )
        }

        val conditionalSubContainer = VBox(8.0).apply {
            padding = Insets(8.0)
            style =
                "-fx-background-color: #eef2f7; -fx-border-color: #d0d7de; -fx-border-radius: 4; -fx-background-radius: 4;"
            children.addAll(treeSelectRow, stageSelectRow)
        }

        // 联动：控制条件区块在未勾选时置灰
        conditionalSubContainer.disableProperty().bind(enableCSCheckBox.selectedProperty().not().or(disableWhen))

        return VBox(6.0).apply {
            children.addAll(enableCSCheckBox, conditionalSubContainer)
        }
    }

    private fun setupButtonActions() {
        newTreeBtn.setOnAction {
            val currentManagerId = store.state.selectedManagerItem?.entity?.id
            val dialog = ConditionTreeDialog(autoCreateDraft = true, managerId = currentManagerId)
            dialog.showAndWait().ifPresent { createdId ->
                refreshConditionTreeOptions()
                selectTreeById(createdId)
                emitConditionalStage()
            }
        }

        editTreeBtn.setOnAction {
            val selectedId = conditionTreeCombo.value?.id
            if (selectedId.isNullOrEmpty()) {
                Alert(Alert.AlertType.WARNING, "请先在下拉列表中选择一个条件树再进行编辑。").apply {
                    headerText = "未选中条件树"
                }.showAndWait()
                return@setOnAction
            }
            val currentManagerId = store.state.selectedManagerItem?.entity?.id
            val dialog = ConditionTreeDialog(initialSelectTreeId = selectedId, managerId = currentManagerId)
            dialog.showAndWait().ifPresent { editedId ->
                refreshConditionTreeOptions()
                selectTreeById(editedId ?: selectedId)
                emitConditionalStage()
            }
        }

        previewTreeBtn.setOnAction {
            val selectedId = conditionTreeCombo.value?.id
            if (selectedId.isNullOrEmpty()) {
                Alert(Alert.AlertType.INFORMATION, "当前未选择条件树。").showAndWait()
                return@setOnAction
            }
            val entity = conditionTreeRepository.findById(selectedId)
            if (entity == null) {
                Alert(Alert.AlertType.ERROR, "未在数据库中找到 ID 为 [$selectedId] 的条件树配置。").showAndWait()
            } else {
                val content =
                    "【条件树 ID】: ${entity.id}\n【条件树名称】: ${entity.name}\n【关联卡组 ID】: ${entity.managerId ?: "(全局共享)"}\n\n【节点配置 JSON 摘要】:\n${entity.configData}"
                Alert(Alert.AlertType.INFORMATION, content).apply {
                    title = "条件树预览"
                    headerText = "条件树配置摘要详情"
                }.showAndWait()
            }
        }

        conditionTreeCombo.setOnShowing {
            val currentId = conditionTreeCombo.value?.id
            refreshConditionTreeOptions()
            selectTreeById(currentId)
        }
    }

    private fun setupControlListeners() {
        stageCombo.valueProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState && newValue != null) {
                val stage = BehaviorDisplayMappers.labelToStageName(newValue)
                store.updateBindingStageOverride(stage)
            }
        }
        replanCombo.valueProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState && newValue != null) {
                val replan = when (newValue) { "是" -> true; "否" -> false; else -> null }
                store.updateBindingReplanAfterUse(replan)
            }
        }
        weightField.textProperty().addListener { _, _, newValue ->
            if (!isUpdatingFromState && newValue != null) {
                newValue.toDoubleOrNull()?.let { store.updateBindingOrderWeight(it) }
            }
        }

        enableCSCheckBox.selectedProperty().addListener { _, _, isSelected ->
            if (!isUpdatingFromState) {
                if (!isSelected) {
                    store.updateBindingConditionalStage(null)
                } else {
                    emitConditionalStage()
                }
            }
        }

        conditionTreeCombo.valueProperty().addListener { _, _, _ -> emitConditionalStage() }
        conditionStageCombo.valueProperty().addListener { _, _, _ -> emitConditionalStage() }
        conditionElseCombo.valueProperty().addListener { _, _, _ -> emitConditionalStage() }
    }

    private fun setupStateObserver() {
        store.stateProperty.addListener { _, _, _ ->
            val idx = store.state.selectedBindingIndex
            val bindings = store.state.currentBindings
            if (idx != null && idx in bindings.indices) {
                updateUiFromBinding(bindings[idx])
            } else {
                clearUiState()
            }
        }
    }

    private fun updateUiFromBinding(binding: lin.rule.tree.CardGroupBinding) {
        isUpdatingFromState = true
        try {
            val stageVal = binding.behaviors.findOverride()?.stageOverride?.name
            val stageLabel = BehaviorDisplayMappers.stageToLabel(stageVal)
            if (stageCombo.value != stageLabel) stageCombo.value = stageLabel

            val replanDisplay = when (binding.behaviors.findOverride()?.replanAfterUse) {
                true -> "是"; false -> "否"; null -> "(不覆盖)"
            }
            if (replanCombo.value != replanDisplay) replanCombo.value = replanDisplay

            val weightVal = binding.behaviors.findOverride()?.orderWeight
            val weightStr = weightVal?.toString() ?: ""
            if (weightField.text != weightStr) weightField.text = weightStr

            val cs = binding.behaviors.findOverride()?.conditionalStage
            if (cs != null) {
                enableCSCheckBox.isSelected = true
                refreshConditionTreeOptions()
                selectTreeById(cs.conditionId)

                val csStageLabel = BehaviorDisplayMappers.stageToLabel(cs.stage.name)
                if (conditionStageCombo.value != csStageLabel) conditionStageCombo.value = csStageLabel

                val csElseLabel = cs.elseStage?.let { BehaviorDisplayMappers.stageToLabel(it.name) }
                if (conditionElseCombo.value != csElseLabel) conditionElseCombo.value = csElseLabel
            } else {
                enableCSCheckBox.isSelected = false
                conditionTreeCombo.value = null
                conditionStageCombo.value = null
                conditionElseCombo.value = null
            }
        } finally {
            isUpdatingFromState = false
        }
    }

    private fun clearUiState() {
        isUpdatingFromState = true
        try {
            stageCombo.value = null
            replanCombo.value = null
            weightField.clear()
            enableCSCheckBox.isSelected = false
            conditionTreeCombo.value = null
            conditionStageCombo.value = null
            conditionElseCombo.value = null
        } finally {
            isUpdatingFromState = false
        }
    }

    private fun refreshConditionTreeOptions() {
        val currentManagerId = store.state.selectedManagerItem?.entity?.id
        val metaList = conditionTreeRepository.findMetaByManagerId(currentManagerId)
        val options = mutableListOf<ConditionTreeOption>()
        for ((id, name) in metaList) {
            options.add(ConditionTreeOption(id, name))
        }
        conditionTreeCombo.items.setAll(options)
    }

    private fun selectTreeById(id: String?) {
        if (id.isNullOrEmpty()) {
            conditionTreeCombo.value = null
            return
        }
        val matched = conditionTreeCombo.items.find { it.id == id }
        if (matched != null) {
            conditionTreeCombo.value = matched
        } else {
            // 不在已有列表中时（自定义/旧数据），创建一个临时回显项
            val tempOption = ConditionTreeOption(id, "自定义条件树 ($id)")
            conditionTreeCombo.items.add(tempOption)
            conditionTreeCombo.value = tempOption
        }
    }

    /** 联动提交到 Store */
    private fun emitConditionalStage() {
        if (isUpdatingFromState) return
        if (!enableCSCheckBox.isSelected) {
            store.updateBindingConditionalStage(null)
            return
        }
        val cid = conditionTreeCombo.value?.id?.takeIf { it.isNotEmpty() }
        if (cid == null) {
            store.updateBindingConditionalStage(null)
            return
        }
        val stageName = BehaviorDisplayMappers.labelToStageName(conditionStageCombo.value)
            ?: return
        val elseName = BehaviorDisplayMappers.labelToStageName(conditionElseCombo.value)
        store.updateBindingConditionalStage(
            ConditionalStageOverride(
                conditionId = cid,
                stage = UseStage.valueOf(stageName),
                elseStage = elseName?.let { UseStage.valueOf(it) }
            )
        )
    }
}

