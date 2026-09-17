package lin.ui.strategy_preset

import javafx.geometry.Insets
import javafx.scene.control.Label
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox

/**
 * 策略预设被禁用用途看板（T-TG-017）。
 *
 * 计算口径（D-TG-015 / T-TG-024-B）：
 * disabledPurposes = 全局启用的用途标签全集 − 本预设已声明的用途标签。
 *
 * ⚠️ 仅统计全局用途树；卡组专属用途树归属即拥有，不计入全集，不受预设裁剪。
 */
class DisabledPurposesBoard : VBox(6.0) {

    private val titleLabel = Label().apply {
        style = "-fx-font-weight: bold; -fx-font-size: 12px;"
    }

    private val tagContainer = FlowPane(6.0, 6.0).apply {
        padding = Insets(4.0, 0.0, 4.0, 0.0)
    }

    private val tipLabel = Label("* 注：卡组专属用途树归属即拥有，跳过预设白名单，不受本预设裁剪。").apply {
        style = "-fx-text-fill: #95a5a6; -fx-font-size: 11px;"
    }

    init {
        padding = Insets(8.0)
        children.addAll(titleLabel, tagContainer, tipLabel)
    }

    /**
     * 根据全局用途全集与预设当前已声明的用途刷新看板。
     */
    fun updatePurposes(
        universe: Set<String>,
        declaredTags: Set<String>,
        tagDisplayNames: Map<String, String> = emptyMap()
    ) {
        val disabled = (universe - declaredTags).sorted()
        tagContainer.children.clear()

        if (disabled.isEmpty()) {
            style =
                "-fx-background-color: #e8f8f5; -fx-border-color: #2ecc71; -fx-border-radius: 4px; -fx-padding: 8px;"
            titleLabel.text = "✅ 全部全局战略用途均已声明保留（无被关闭的全局兜底）"
            titleLabel.style = "-fx-text-fill: #27ae60; -fx-font-weight: bold;"
            tagContainer.isVisible = false
            tagContainer.isManaged = false
        } else {
            style =
                "-fx-background-color: #fef9e7; -fx-border-color: #f39c12; -fx-border-radius: 4px; -fx-padding: 8px;"
            titleLabel.text = "⚠️ 以下 ${disabled.size} 个全局战略用途未在预设中声明，对应全局用途树将被全部关闭："
            titleLabel.style = "-fx-text-fill: #d35400; -fx-font-weight: bold;"
            tagContainer.isVisible = true
            tagContainer.isManaged = true

            for (tag in disabled) {
                val displayName = tagDisplayNames[tag] ?: tag
                val badge = Label(displayName).apply {
                    style =
                        "-fx-background-color: #e74c3c; -fx-text-fill: white; -fx-padding: 2 6; -fx-background-radius: 3px; -fx-font-size: 11px; -fx-font-weight: bold;"
                    tooltip = javafx.scene.control.Tooltip(tag)
                }
                tagContainer.children.add(badge)
            }
        }
    }
}
