package lin.condition_tree.ui.strategy

import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.condition_tree.ui.components.OrthogonalConditionDialog
import lin.rule.condition.ConditionMeta
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.tree_config.ui.LogicNodeType
import lin.tree_config.ui.LogicNodeWrapper
import lin.ui.components.PropertyEditorStrategy
import java.util.*

class ConditionPropertyEditorStrategy(
    private val conditionRegistry: ConditionRegistry
) : PropertyEditorStrategy<ConditionPayload> {

    private val orthoMeta = ConditionMeta(
        conditionId = "orthogonal_condition",
        name = "正交条件 (配置型)",
        desc = "使用数据源与算子灵活组合的配置型条件",
        fields = emptyList()
    )

    override fun canEdit(type: LogicNodeType): Boolean {
        return type == LogicNodeType.LEAF || type == LogicNodeType.BRANCH
    }

    override fun render(panel: VBox, wrapper: LogicNodeWrapper<ConditionPayload>, onChanged: () -> Unit) {
        panel.children.clear()

        val payload = wrapper.payload
        val isOrtho = payload is ConditionPayload.OrthogonalRef
        val conditionId =
            if (isOrtho) "orthogonal_condition" else (payload as? ConditionPayload.ConditionRef)?.conditionId
        val refId = payload?.refId
        val isBranch = wrapper.type == LogicNodeType.BRANCH

        panel.children.addAll(buildHeader(conditionId, refId, isBranch))
        panel.children.add(Separator())

        val allConditions = listOf(orthoMeta) + conditionRegistry.metadataList()
        val conditionCombo = createConditionCombo(allConditions, conditionId)

        val detailContainer = VBox(8.0)
        panel.children.addAll(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(Label("条件:"), conditionCombo)
                HBox.setHgrow(conditionCombo, Priority.ALWAYS)
            },
            detailContainer
        )

        // 渲染联动编辑区
        fun renderDetail(selected: ConditionMeta) {
            detailContainer.children.clear()
            if (selected.conditionId == "orthogonal_condition") {
                val btn = Button("配置正交条件详情...")
                val currentRef = wrapper.payload as? ConditionPayload.OrthogonalRef
                val summaryLabel = Label(
                    currentRef?.let { "已配置: ${it.sourceId} -> ${it.operatorId}" } ?: "未配置正交条件"
                ).apply {
                    style =
                        if (currentRef != null) "-fx-text-fill: #333;" else "-fx-text-fill: #888; -fx-font-style: italic;"
                }

                btn.setOnAction {
                    val dialog = OrthogonalConditionDialog(wrapper.payload as? ConditionPayload.OrthogonalRef)
                    val res = dialog.showAndWait()
                    if (res.isPresent) {
                        wrapper.payload = res.get()
                        summaryLabel.text = "已配置: ${res.get().sourceId} -> ${res.get().operatorId}"
                        summaryLabel.style = "-fx-text-fill: #333;"
                        onChanged()
                    }
                }
                detailContainer.children.addAll(btn, summaryLabel)
            } else {
                // 普通条件在条件树编辑阶段没有参数输入，具体参数在评估树阶段作为 arguments 平铺渲染
                detailContainer.children.add(Label("普通硬编码条件，可在引用该条件树的评估树叶子节点中统一配置参数。").apply {
                    style = "-fx-text-fill: #888; -fx-font-size: 11px; -fx-font-style: italic;"
                    isWrapText = true
                })
            }
        }

        conditionCombo.selectionModel.selectedItemProperty().addListener { _, _, selectedCondition ->
            if (selectedCondition != null) {
                if (selectedCondition.conditionId == "orthogonal_condition") {
                    val currentPayload = wrapper.payload
                    if (currentPayload !is ConditionPayload.OrthogonalRef) {
                        wrapper.payload = ConditionPayload.OrthogonalRef(
                            sourceId = "",
                            operatorId = "",
                            refId = "orthogonal_${UUID.randomUUID().toString().substring(0, 4)}",
                            args = emptyMap()
                        )
                    }
                } else {
                    val newRefId = if (conditionId == selectedCondition.conditionId) {
                        refId ?: selectedCondition.conditionId
                    } else {
                        generateUniqueRefId(selectedCondition.conditionId)
                    }
                    wrapper.payload = ConditionPayload.ConditionRef(
                        conditionId = selectedCondition.conditionId,
                        refId = newRefId
                    )
                }
                renderDetail(selectedCondition)
                onChanged()
            }
        }

        conditionCombo.value?.let { renderDetail(it) }
    }

    private fun generateUniqueRefId(conditionId: String): String {
        return "${conditionId}_${System.currentTimeMillis().toString(16).takeLast(4)}"
    }

    private fun buildHeader(conditionId: String?, refId: String?, isBranch: Boolean): List<javafx.scene.Node> {
        val typeLabel = if (isBranch) "Branch 节点" else "条件节点"
        val displayCondId = if (conditionId == "orthogonal_condition") "配置型正交条件" else (conditionId ?: "<未命名>")
        val nodes = mutableListOf<javafx.scene.Node>(
            Label("$typeLabel (refId: ${refId ?: "<未命名>"}, 类型: $displayCondId)").apply {
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

    private fun createConditionCombo(
        allConditions: List<ConditionMeta>,
        selectedId: String?
    ): ComboBox<ConditionMeta> {
        return ComboBox<ConditionMeta>().apply {
            maxWidth = Double.MAX_VALUE
            items.addAll(allConditions)
            setCellFactory { createConditionCell() }
            buttonCell = createConditionCell()
            allConditions.firstOrNull { it.conditionId == selectedId }
                ?.let { selectionModel.select(it) }
        }
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
