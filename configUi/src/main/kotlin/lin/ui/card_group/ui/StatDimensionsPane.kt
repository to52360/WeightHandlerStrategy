package lin.ui.card_group.ui

import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import lin.domain.MatchState

/**
 * 统计维度多选框面板：RECORD_PLAY 的子配置。
 * 内部管理 CheckBox 矩阵（duration × key 笛卡尔积），写通路自动通知 store，
 * 读通路通过 [syncFromConfig] 恢复选择状态。
 */
class StatDimensionsPane(
    private val store: WorkbenchStore,
    disableWhen: ObservableBooleanValue
) {
    val node: VBox = VBox(5.0).apply {
        padding = Insets(0.0, 0.0, 0.0, 15.0)
    }
    private val dimCheckMap = mutableMapOf<Pair<MatchState.StatDimensionKey, MatchState.StatDuration>, CheckBox>()

    var isVisible: Boolean
        get() = node.isVisible
        set(value) { node.isVisible = value }

    init {
        MatchState.StatDuration.entries.forEach { duration ->
            val row = HBox(10.0).apply { alignment = Pos.CENTER_LEFT }
            row.children.add(Label("${duration.name}: "))
            MatchState.StatDimensionKey.entries.forEach { key ->
                val cb = CheckBox(key.name).apply {
                    disableProperty().bind(disableWhen)
                }
                cb.selectedProperty().addListener { _, _, _ ->
                    val selected = dimCheckMap.filterValues { it.isSelected }.keys
                        .map { MatchState.StatDimension(it.first, it.second) }
                    store.updateBindingStatDimensions(selected)
                }
                dimCheckMap[key to duration] = cb
                row.children.add(cb)
            }
            node.children.add(row)
        }
    }

    fun syncFromConfig(extraConfig: Map<String, Any>) {
        val dims = parseStatDimensionsFromConfig(extraConfig)
        dimCheckMap.forEach { (pair, cb) ->
            val expected = dims.any { it.key == pair.first && it.duration == pair.second }
            if (cb.isSelected != expected) cb.isSelected = expected
        }
    }

    fun clearSelection() {
        dimCheckMap.values.forEach { it.isSelected = false }
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
