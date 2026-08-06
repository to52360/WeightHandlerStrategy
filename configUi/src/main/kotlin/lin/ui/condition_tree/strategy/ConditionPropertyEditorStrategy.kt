package lin.ui.condition_tree.strategy

import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.rule.condition.ConditionMeta
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.ui.components.PropertyEditorStrategy
import lin.ui.condition_tree.components.OrthogonalConditionDialog
import lin.ui.tree_config.DynamicFieldForm
import lin.ui.tree_config.FieldValueReader
import lin.ui.tree_config.LogicNodeType
import lin.ui.tree_config.LogicNodeWrapper
import java.util.*

class ConditionPropertyEditorStrategy(
    private val conditionRegistry: ConditionRegistry
) : PropertyEditorStrategy<ConditionPayload> {

    private val dynamicFieldForm by lazy { DynamicFieldForm() }

    override fun canEdit(type: LogicNodeType): Boolean {
        return type == LogicNodeType.LEAF || type == LogicNodeType.BRANCH
    }

    override fun render(panel: VBox, wrapper: LogicNodeWrapper<ConditionPayload>, onChanged: () -> Unit) {
        panel.children.clear()

        val payload = wrapper.payload
        val isOrtho = payload is ConditionPayload.PipelineRef
        val refId = payload?.refId
        val isBranch = wrapper.type == LogicNodeType.BRANCH

        panel.children.addAll(buildHeader(isOrtho, refId, isBranch))
        panel.children.add(Separator())

        // 类型切换区（正交管道型 / 编码硬编码型）
        val typeToggleBox = HBox(8.0).apply { alignment = Pos.CENTER_LEFT; maxWidth = Double.MAX_VALUE }
        val typeLabel = Label(if (isOrtho) "条件类型: 正交(管道型)" else "条件类型: 编码(硬编码型)")
        val toggleBtn = Button(if (isOrtho) "切换为编码条件" else "切换为正交条件")
        typeToggleBox.children.addAll(typeLabel, toggleBtn)
        panel.children.add(typeToggleBox)

        val detailContainer = VBox(8.0)
        panel.children.add(detailContainer)

        // 渲染联动编辑区（按 payload 类型而非字符串分发）
        fun renderDetail() {
            detailContainer.children.clear()
            if (isOrtho) {
                renderOrthogonalDetail(detailContainer, wrapper, onChanged)
            } else {
                renderCodedDetail(detailContainer, wrapper, onChanged)
            }
        }

        toggleBtn.setOnAction {
            if (isOrtho) {
                // 正交 → 编码：切片到第一个注册条件
                val firstCond = conditionRegistry.metadataList().firstOrNull()
                val newRefId = generateUniqueRefId(firstCond?.conditionId ?: "unknown")
                wrapper.payload = ConditionPayload.ConditionRef(
                    conditionId = firstCond?.conditionId ?: "",
                    refId = newRefId
                )
            } else {
                // 编码 → 正交
                wrapper.payload = ConditionPayload.PipelineRef(
                    sourceId = "",
                    transforms = emptyList(),
                    operatorId = "",
                    operatorArgs = emptyMap(),
                    refId = "orthogonal_${UUID.randomUUID().toString().substring(0, 4)}"
                )
            }
            onChanged()
            // 重新渲染整个面板
            panel.children.clear()
            render(panel, wrapper, onChanged)
        }

        renderDetail()
    }

    private fun renderOrthogonalDetail(
        container: VBox,
        wrapper: LogicNodeWrapper<ConditionPayload>,
        onChanged: () -> Unit
    ) {
        val btn = Button("配置正交条件详情...")
        val currentRef = wrapper.payload as? ConditionPayload.PipelineRef
        val summaryLabel = Label(
            currentRef?.let { "已配置: ${it.sourceId} -> ${it.operatorId}" } ?: "未配置正交条件"
        ).apply {
            style = if (currentRef != null) "-fx-text-fill: #333;" else "-fx-text-fill: #888; -fx-font-style: italic;"
        }

        btn.setOnAction {
            val dialog = OrthogonalConditionDialog(wrapper.payload as? ConditionPayload.PipelineRef)
            val res = dialog.showAndWait()
            if (res.isPresent) {
                wrapper.payload = res.get()
                summaryLabel.text = "已配置: ${res.get().sourceId} -> ${res.get().operatorId}"
                summaryLabel.style = "-fx-text-fill: #333;"
                onChanged()
            }
        }
        container.children.addAll(btn, summaryLabel)
    }

    private fun renderCodedDetail(
        container: VBox,
        wrapper: LogicNodeWrapper<ConditionPayload>,
        onChanged: () -> Unit
    ) {
        val allConditions = conditionRegistry.metadataList()
        val currentRef = wrapper.payload as? ConditionPayload.ConditionRef
        val currentConditionId = currentRef?.conditionId

        val conditionCombo = ComboBox<ConditionMeta>().apply {
            maxWidth = Double.MAX_VALUE
            items.addAll(allConditions)
            setCellFactory { createConditionCell() }
            buttonCell = createConditionCell()
            allConditions.firstOrNull { it.conditionId == currentConditionId }
                ?.let { selectionModel.select(it) }
        }

        // 动态参数字段容器：按当前选中条件的 FieldSpec 渲染输入控件
        val argsContainer = VBox(8.0).apply {
            style = "-fx-padding: 4 0 0 0;"
        }

        fun rebuildArgsFields(meta: ConditionMeta?) {
            argsContainer.children.clear()
            val args = (wrapper.payload as? ConditionPayload.ConditionRef)?.args ?: emptyMap()
            if (meta != null && meta.fields.isNotEmpty()) {
                val form = dynamicFieldForm.build(
                    specs = meta.fields.map { it.fieldSpec },
                    existingValues = FieldValueReader { prop -> args[prop] },
                    onFieldChanged = { prop, value ->
                        val cur = wrapper.payload as? ConditionPayload.ConditionRef ?: return@build
                        val newArgs = cur.args.toMutableMap()
                        newArgs[prop] = value
                        wrapper.payload = cur.copy(args = newArgs)
                        onChanged()
                    }
                )
                argsContainer.children.add(form)
            } else if (meta != null) {
                argsContainer.children.add(
                    Label("此条件无额外参数，无需填写。").apply {
                        style = "-fx-text-fill: #888; -fx-font-size: 11px; -fx-font-style: italic;"
                    }
                )
            }
        }

        // 初始渲染当前条件的参数字段（打开已保存树时，参数由服务层从旁挂表合并回显）
        val currentMeta = allConditions.firstOrNull { it.conditionId == currentConditionId }
        rebuildArgsFields(currentMeta)

        conditionCombo.selectionModel.selectedItemProperty().addListener { _, _, selectedCondition ->
            if (selectedCondition != null && selectedCondition.conditionId != currentConditionId) {
                val newRefId = generateUniqueRefId(selectedCondition.conditionId)
                wrapper.payload = ConditionPayload.ConditionRef(
                    conditionId = selectedCondition.conditionId,
                    refId = newRefId,
                    args = emptyMap()
                )
                rebuildArgsFields(selectedCondition)
                onChanged()
            }
        }

        val hint = Label("参数保存到条件树参数表（树 JSON 保持纯结构，排序/AuraBoost 引用时自动注入）。").apply {
            style = "-fx-text-fill: #888; -fx-font-size: 11px; -fx-font-style: italic;"
            isWrapText = true
        }

        container.children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(Label("编码条件:"), conditionCombo)
                HBox.setHgrow(conditionCombo, Priority.ALWAYS)
            },
            argsContainer,
            hint
        )
    }

    private fun generateUniqueRefId(conditionId: String): String {
        return "${conditionId}_${System.currentTimeMillis().toString(16).takeLast(4)}"
    }

    private fun buildHeader(isOrtho: Boolean, refId: String?, isBranch: Boolean): List<javafx.scene.Node> {
        val typeLabel = if (isBranch) "Branch 节点" else "条件节点"
        val displayType = if (isOrtho) "配置型正交条件" else "编码型条件"
        val nodes = mutableListOf<javafx.scene.Node>(
            Label("$typeLabel (refId: ${refId ?: "<未命名>"}, 类型: $displayType)").apply {
                style = "-fx-font-weight: bold;"
            }
        )
        if (isBranch) {
            nodes.add(
                Label("此条件决定分支走向：子节点[0] = onTrue, [1] = onFalse").apply {
                    style = "-fx-text-fill: #888; -fx-font-size: 11px;"
                }
            )
        }
        return nodes
    }

    private fun createConditionCell(): ListCell<ConditionMeta> {
        return object : ListCell<ConditionMeta>() {
            override fun updateItem(item: ConditionMeta?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else item.name?.takeIf { it.isNotBlank() } ?: item.conditionId
            }
        }
    }
}
