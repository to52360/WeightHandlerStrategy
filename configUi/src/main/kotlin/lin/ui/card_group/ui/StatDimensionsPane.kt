package lin.ui.card_group.ui

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.domain.MatchState

/**
 * 统计维度配置面板：RECORD_PLAY 的子配置。
 * - 记录周期 (StatDuration)：单选二选一（整局 GAME vs 本回合 ROUND）。
 * - 统计维度 (StatDimensionKey)：正交多选（单卡 CARD / 卡牌分组 GROUP / 意图标签 PURPOSE）。
 */
class StatDimensionsPane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) {
    val node: VBox = VBox(8.0).apply {
        padding = Insets(8.0, 12.0, 8.0, 12.0)
        style =
            "-fx-background-color: #f8f9fa; -fx-border-color: #dee2e6; -fx-border-radius: 4; -fx-background-radius: 4;"
    }

    private val durationToggleGroup = ToggleGroup()
    private val gameRadio = RadioButton(BehaviorDisplayMappers.durationToLabel(MatchState.StatDuration.GAME)).apply {
        toggleGroup = durationToggleGroup
        isSelected = true
        disableProperty().bind(disableWhen)
    }
    private val roundRadio = RadioButton(BehaviorDisplayMappers.durationToLabel(MatchState.StatDuration.ROUND)).apply {
        toggleGroup = durationToggleGroup
        disableProperty().bind(disableWhen)
    }

    private val keyCheckMap = mutableMapOf<MatchState.StatDimensionKey, CheckBox>()
    private var isUpdatingFromSync = false

    var isVisible: Boolean
        get() = node.isVisible
        set(value) {
            node.isVisible = value
            node.isManaged = value
        }

    init {
        val durationRow = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            val label = Label("记录周期 (单选):").apply {
                prefWidth = 130.0
                style = "-fx-font-weight: bold; -fx-text-fill: #495057;"
            }
            children.addAll(label, gameRadio, roundRadio)
        }

        val dimensionRow = HBox(12.0).apply {
            alignment = Pos.CENTER_LEFT
            val label = Label("统计维度 (正交多选):").apply {
                prefWidth = 130.0
                style = "-fx-font-weight: bold; -fx-text-fill: #495057;"
            }
            children.add(label)

            MatchState.StatDimensionKey.entries.forEach { key ->
                val cb = CheckBox(BehaviorDisplayMappers.dimensionKeyToLabel(key)).apply {
                    disableProperty().bind(disableWhen)
                }
                cb.selectedProperty().addListener { _, _, _ -> notifyStore() }
                keyCheckMap[key] = cb
                children.add(cb)
            }
        }

        gameRadio.selectedProperty().addListener { _, _, _ -> notifyStore() }
        roundRadio.selectedProperty().addListener { _, _, _ -> notifyStore() }

        node.children.addAll(durationRow, dimensionRow)
    }

    private fun notifyStore() {
        if (isUpdatingFromSync) return
        val currentDuration = if (roundRadio.isSelected) MatchState.StatDuration.ROUND else MatchState.StatDuration.GAME
        val selectedKeys = keyCheckMap.filterValues { it.isSelected }.keys
        val dimensions = selectedKeys.map { MatchState.StatDimension(it, currentDuration) }
        store.updateBindingStatDimensions(dimensions)
    }

    fun syncFromConfig(extraConfig: Map<String, Any>) {
        val dims = parseStatDimensionsFromConfig(extraConfig)
        isUpdatingFromSync = true
        try {
            if (dims.isEmpty()) {
                gameRadio.isSelected = true
                keyCheckMap.values.forEach { it.isSelected = false }
            } else {
                val dur = dims.firstOrNull()?.duration ?: MatchState.StatDuration.GAME
                if (dur == MatchState.StatDuration.ROUND) {
                    roundRadio.isSelected = true
                } else {
                    gameRadio.isSelected = true
                }
                keyCheckMap.forEach { (key, cb) ->
                    cb.isSelected = dims.any { it.key == key }
                }
            }
        } finally {
            isUpdatingFromSync = false
        }
    }

    fun clearSelection() {
        isUpdatingFromSync = true
        try {
            gameRadio.isSelected = true
            keyCheckMap.values.forEach { it.isSelected = false }
        } finally {
            isUpdatingFromSync = false
        }
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        private fun parseStatDimensionsFromConfig(extraConfig: Map<String, Any>): List<MatchState.StatDimension> {
            val raw = extraConfig["stat_dimensions"] as? List<*> ?: return emptyList()
            return raw.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val keyStr = map["key"] as? String ?: return@mapNotNull null
                val durStr = map["duration"] as? String ?: return@mapNotNull null
                try {
                    MatchState.StatDimension(
                        MatchState.StatDimensionKey.valueOf(keyStr),
                        MatchState.StatDuration.valueOf(durStr)
                    )
                } catch (_: Exception) { null }
            }
        }
    }
}
