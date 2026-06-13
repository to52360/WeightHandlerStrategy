package lin.condition_tree.ui.strategy

import javafx.geometry.Pos
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.Separator
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.rule.condition.ConditionMeta
import lin.rule.condition.ConditionPayload
import lin.rule.condition.ConditionRegistry
import lin.tree_config.ui.LogicNodeType
import lin.tree_config.ui.LogicNodeWrapper
import lin.ui.components.PropertyEditorStrategy

class ConditionPropertyEditorStrategy(
    private val conditionRegistry: ConditionRegistry
) : PropertyEditorStrategy<ConditionPayload> {

    override fun canEdit(type: LogicNodeType): Boolean {
        return type == LogicNodeType.LEAF || type == LogicNodeType.BRANCH
    }

    override fun render(panel: VBox, wrapper: LogicNodeWrapper<ConditionPayload>, onChanged: () -> Unit) {
        panel.children.clear()

        val payload = wrapper.payload
        val conditionId = (payload as? ConditionPayload.ConditionRef)?.conditionId
        val refId = payload?.refId
        val isBranch = wrapper.type == LogicNodeType.BRANCH

        panel.children.addAll(buildHeader(conditionId, refId, isBranch))
        panel.children.add(Separator())

        val allConditions = conditionRegistry.metadataList()
        val conditionCombo = createConditionCombo(allConditions, conditionId)

        conditionCombo.selectionModel.selectedItemProperty().addListener { _, _, selectedCondition ->
            if (selectedCondition != null) {
                // 选择条件时自动生成唯一 refId：conditionId_序号
                val newRefId = if (conditionId == selectedCondition.conditionId) {
                    refId ?: selectedCondition.conditionId
                } else {
                    generateUniqueRefId(selectedCondition.conditionId)
                }
                wrapper.payload = ConditionPayload.ConditionRef(
                    conditionId = selectedCondition.conditionId,
                    refId = newRefId
                )
                onChanged()
            }
        }

        panel.children.add(
            HBox(8.0).apply {
                alignment = Pos.CENTER_LEFT
                children.addAll(Label("条件:"), conditionCombo)
                HBox.setHgrow(conditionCombo, Priority.ALWAYS)
            }
        )
    }

    private fun generateUniqueRefId(conditionId: String): String {
        return "${conditionId}_${System.currentTimeMillis().toString(16).takeLast(4)}"
    }

    private fun buildHeader(conditionId: String?, refId: String?, isBranch: Boolean): List<javafx.scene.Node> {
        val typeLabel = if (isBranch) "Branch 节点" else "条件节点"
        val nodes = mutableListOf<javafx.scene.Node>(
            Label("$typeLabel (refId: ${refId ?: "<未命名>"}, conditionId: ${conditionId ?: "<未命名>"})").apply {
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
