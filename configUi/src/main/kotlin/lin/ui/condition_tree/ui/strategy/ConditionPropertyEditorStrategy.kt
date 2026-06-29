package lin.ui.condition_tree.ui.strategy

import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.rule.condition.ConditionMeta
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.ui.components.PropertyEditorStrategy
import lin.ui.condition_tree.ui.components.OrthogonalConditionDialog
import lin.ui.tree_config.ui.LogicNodeType
import lin.ui.tree_config.ui.LogicNodeWrapper
import java.util.*

class ConditionPropertyEditorStrategy(
    private val conditionRegistry: ConditionRegistry
) : PropertyEditorStrategy<ConditionPayload> {

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
        val currentConditionId = (wrapper.payload as? ConditionPayload.ConditionRef)?.conditionId
        val refId = wrapper.payload?.refId

        val conditionCombo = ComboBox<ConditionMeta>().apply {
            maxWidth = Double.MAX_VALUE
            items.addAll(allConditions)
            setCellFactory { createConditionCell() }
            buttonCell = createConditionCell()
            allConditions.firstOrNull { it.conditionId == currentConditionId }
                ?.let { selectionModel.select(it) }
        }

        conditionCombo.selectionModel.selectedItemProperty().addListener { _, _, selectedCondition ->
            if (selectedCondition != null && selectedCondition.conditionId != currentConditionId) {
                val newRefId = generateUniqueRefId(selectedCondition.conditionId)
                wrapper.payload = ConditionPayload.ConditionRef(
                    conditionId = selectedCondition.conditionId,
                    refId = newRefId
                )
                onChanged()
            }
        }

        container.children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(Label("编码条件:"), conditionCombo)
                HBox.setHgrow(conditionCombo, Priority.ALWAYS)
            },
            Label("普通硬编码条件，可在引用该条件树的评估树叶子节点中统一配置参数。").apply {
                style = "-fx-text-fill: #888; -fx-font-size: 11px; -fx-font-style: italic;"
                isWrapText = true
            }
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
