package lin.ui.tree_config.strategy

import javafx.scene.layout.VBox
import lin.rule.score.ScoreOperatorRegistry
import lin.rule.tree.EvaluatorLeafConfig
import lin.rule.tree.EvaluatorLeafKind
import lin.rule.tree.EvaluatorLeafMeta
import lin.ui.tree_config.DynamicFieldForm

interface LeafEditor {
    fun render(ctx: LeafEditContext): VBox
}

class LeafEditContext(
    val nodeId: String,
    val leafConfigs: MutableMap<String, EvaluatorLeafConfig>,
    val selectedLeaf: EvaluatorLeafMeta,
    val isBranch: Boolean,
    val onChanged: () -> Unit,
    val dynamicFieldForm: DynamicFieldForm,
    val scoreOperatorRegistry: ScoreOperatorRegistry
)

class LeafEditorRegistry {
    private val map = mutableMapOf<EvaluatorLeafKind, LeafEditor>()

    fun register(kind: EvaluatorLeafKind, editor: LeafEditor) {
        map[kind] = editor
    }

    fun editorFor(kind: EvaluatorLeafKind): LeafEditor =
        map[kind] ?: error("No LeafEditor registered for kind: $kind")
}
