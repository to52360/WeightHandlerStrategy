package lin.ui.tree_config.components

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.rule.orthogonal.Transform
import lin.rule.orthogonal.TransformCall
import lin.ui.tree_config.DynamicFieldForm
import kotlin.reflect.KType
import kotlin.reflect.full.isSubtypeOf

class TransformRow(
    val container: VBox,
    val transformCombo: ComboBox<Transform<*, *>>,
    val argsContainer: VBox,
    val args: MutableMap<String, Any> = mutableMapOf(),
    var loadedTransform: Transform<*, *>? = null
)

class OrthogonalPipelineEditor(
    private val allTransforms: List<Transform<*, *>>,
    private val dynamicFieldForm: DynamicFieldForm,
    private val onFinalTypeChanged: (KType?) -> Unit
) : VBox(8.0) {

    private val transformRows = mutableListOf<TransformRow>()
    private val transformsContainer = VBox(6.0)
    private var dataSourceType: KType? = null
    private var updatingPipeline = false

    init {
        val addTransformBtn = Button("添加转换器").apply {
            setOnAction {
                addTransformRow()
                updatePipeline()
            }
        }
        children.addAll(transformsContainer, addTransformBtn)
    }

    fun onDataSourceChanged(newDsType: KType?) {
        dataSourceType = newDsType
        transformRows.clear()
        transformsContainer.children.clear()
        updatePipeline()
    }

    fun loadTransforms(transformsList: List<TransformCall>) {
        transformRows.clear()
        transformsContainer.children.clear()

        for (call in transformsList) {
            val tf = allTransforms.firstOrNull { it.id == call.transformId }
            if (tf != null) {
                addTransformRow(tf, call.args)
            }
        }
        updatePipeline()
    }

    fun getTransformCalls(): List<TransformCall> {
        return transformRows.map { row ->
            TransformCall(
                transformId = row.transformCombo.value!!.id,
                args = HashMap(row.args)
            )
        }
    }

    private fun reindexTransformLabels() {
        for (i in transformRows.indices) {
            val row = transformRows[i]
            val topBar = row.container.children.firstOrNull() as? HBox
            val label = topBar?.children?.firstOrNull() as? Label
            label?.text = "转换器 ${i + 1}:"
        }
    }

    private fun updatePipeline() {
        updatingPipeline = true
        try {
            var currentType: KType? = dataSourceType

            for (i in transformRows.indices) {
                val row = transformRows[i]
                if (currentType == null) {
                    row.transformCombo.items.clear()
                    row.transformCombo.isDisable = true
                    row.argsContainer.children.clear()
                    row.args.clear()
                    row.loadedTransform = null
                    continue
                }

                val compatibleTransforms = allTransforms.filter { isCompatible(it.inputType, currentType!!) }
                val selected = row.transformCombo.value ?: row.loadedTransform
                row.transformCombo.items.setAll(compatibleTransforms)
                row.transformCombo.isDisable = false

                val target = if (selected != null) {
                    compatibleTransforms.firstOrNull { it.id == selected.id }
                } else null

                if (target != null) {
                    row.transformCombo.selectionModel.select(target)
                    row.loadedTransform = null
                    currentType = target.outputType

                    if (row.argsContainer.children.isEmpty() && target.fields.isNotEmpty()) {
                        val form = dynamicFieldForm.build(
                            specs = target.fields,
                            existingValues = { prop -> row.args[prop] }
                        ) { prop, value ->
                            row.args[prop] = value
                        }
                        row.argsContainer.children.add(form)
                    }
                } else {
                    row.transformCombo.selectionModel.clearSelection()
                    row.argsContainer.children.clear()
                    row.args.clear()
                    row.loadedTransform = null
                    currentType = null
                }
            }

            onFinalTypeChanged(currentType)
        } finally {
            updatingPipeline = false
        }
    }

    private fun addTransformRow(
        loadedTransform: Transform<*, *>? = null,
        loadedArgs: Map<String, Any>? = null
    ): TransformRow {
        val rowContainer = VBox(4.0).apply {
            padding = Insets(5.0)
            style = "-fx-border-color: #eee; -fx-border-width: 0 0 1px 0;"
        }
        val topBar = HBox(8.0).apply { alignment = Pos.CENTER_LEFT }
        val transformCombo = ComboBox<Transform<*, *>>().apply {
            maxWidth = Double.MAX_VALUE
            setCellFactory { createTransformCell() }
            buttonCell = createTransformCell()
        }
        HBox.setHgrow(transformCombo, Priority.ALWAYS)

        val argsContainer = VBox(4.0).apply { padding = Insets(0.0, 0.0, 0.0, 20.0) }
        val rowArgs = mutableMapOf<String, Any>()
        if (loadedArgs != null) rowArgs.putAll(loadedArgs)

        val newRow = TransformRow(
            container = rowContainer,
            transformCombo = transformCombo,
            argsContainer = argsContainer,
            args = rowArgs,
            loadedTransform = loadedTransform
        )

        val deleteBtn = Button("删除").apply {
            setOnAction {
                transformRows.remove(newRow)
                transformsContainer.children.remove(rowContainer)
                reindexTransformLabels()
                updatePipeline()
            }
        }

        val indexLabel = Label("转换器 ${transformRows.size + 1}:").apply {
            prefWidth = 140.0
            minWidth = 140.0
        }
        topBar.children.addAll(indexLabel, transformCombo, deleteBtn)
        rowContainer.children.addAll(topBar, argsContainer)

        transformCombo.selectionModel.selectedItemProperty().addListener { _, _, newT ->
            if (updatingPipeline) return@addListener
            argsContainer.children.clear()
            rowArgs.clear()
            if (newT != null) {
                val specs = newT.fields
                if (specs.isNotEmpty()) {
                    val form = dynamicFieldForm.build(
                        specs = specs,
                        existingValues = { prop -> rowArgs[prop] }
                    ) { prop, value ->
                        rowArgs[prop] = value
                    }
                    argsContainer.children.add(form)
                }
            }
            updatePipeline()
        }

        transformRows.add(newRow)
        transformsContainer.children.add(rowContainer)
        return newRow
    }

    private fun createTransformCell(): ListCell<Transform<*, *>> {
        return object : ListCell<Transform<*, *>>() {
            override fun updateItem(item: Transform<*, *>?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null
                else "${item.name} (${item.id})"
            }
        }
    }

    private fun isCompatible(inputType: KType, outputType: KType): Boolean {
        return inputType == outputType || outputType.isSubtypeOf(inputType)
    }
}
