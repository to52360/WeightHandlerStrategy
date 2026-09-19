package lin.ui.card_group

import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import lin.repository.card_group.PresetDetail
import lin.ui.WorkbenchNavigator
import lin.ui.strategy_preset.StrategyPresetExtension

/**
 * 预设详细信息弹窗（T-TG-043 优化）。
 *
 * 点击卡组分组中的「📋 预设详情...」触发，弹窗展示：
 * 1. 预设基础信息（名称、ID、描述）；
 * 2. 声明的用途与全局用途树统计；
 * 3. 时序规则与惜售门槛声明统计；
 * 4. 🚫 预设禁用的用途（未声明即禁用，纯中文标签，hover 提示 tagId）；
 * 5. 「🔗 跳转编辑预设」按钮，可快捷直达策略预设工作台定位并编辑。
 */
class CardGroupPresetDetailDialog(
    private val presetDetail: PresetDetail,
    purposeUniverse: Set<String>,
    tagDisplayNames: Map<String, String>,
    private val navigator: WorkbenchNavigator
) : Dialog<Unit>() {

    init {
        val preset = presetDetail.preset
        title = "📋 策略预设详情 - ${preset.name}"
        headerText = "当前卡组绑定的策略预设基底详细配置与生效范围。"

        val dialogPane = this.dialogPane
        dialogPane.prefWidth = 560.0
        dialogPane.buttonTypes.add(ButtonType.CLOSE)

        val declaredTags = presetDetail.treeSelections.keys
        val totalTrees = presetDetail.treeSelections.values.sumOf { it.size }
        val timingCount = presetDetail.timings.size
        val disabledTags = (purposeUniverse - declaredTags).sorted()

        val contentBox = VBox(12.0).apply {
            padding = Insets(10.0)

            // 1. 基础信息卡片
            val infoCard = VBox(6.0).apply {
                style = "-fx-background-color: #f8fafc; -fx-border-color: #e2e8f0; -fx-border-radius: 4px; -fx-padding: 10px;"
                children.addAll(
                    HBox(10.0,
                        Label("预设名称:").apply { style = "-fx-font-weight: bold; -fx-text-fill: #34495e;" },
                        Label(preset.name).apply { style = "-fx-font-weight: bold; -fx-text-fill: #2c3e50;" }
                    ),
                    HBox(10.0,
                        Label("预设 ID:").apply { style = "-fx-font-weight: bold; -fx-text-fill: #34495e;" },
                        Label(preset.id).apply { style = "-fx-text-fill: #7f8c8d; -fx-font-family: monospace;" }
                    ),
                    HBox(10.0,
                        Label("预设描述:").apply { style = "-fx-font-weight: bold; -fx-text-fill: #34495e;" },
                        Label(preset.description?.takeIf { it.isNotBlank() } ?: "暂无描述").apply {
                            style = "-fx-text-fill: #64748b;"
                            isWrapText = true
                        }
                    )
                )
            }

            // 2. 统计行
            val statsBox = HBox(20.0).apply {
                alignment = Pos.CENTER_LEFT
                style = "-fx-padding: 4 0 4 0;"
                children.addAll(
                    Label("🌲 声明用途: ${declaredTags.size} 个").apply {
                        style = "-fx-font-weight: bold; -fx-text-fill: #27ae60;"
                    },
                    Label("保留用途树: $totalTrees 棵").apply {
                        style = "-fx-font-weight: bold; -fx-text-fill: #2980b9;"
                    },
                    Label("⏱️ 时序声明: $timingCount 项").apply {
                        style = "-fx-font-weight: bold; -fx-text-fill: #8e44ad;"
                    }
                )
            }

            // 3. 声明用途列表
            val declaredSection = VBox(4.0).apply {
                val flow = FlowPane(6.0, 6.0).apply { alignment = Pos.CENTER_LEFT }
                if (declaredTags.isEmpty()) {
                    flow.children.add(Label("未声明任何用途（所有用途树均被禁用）").apply {
                        style = "-fx-font-size: 11px; -fx-text-fill: #e74c3c;"
                    })
                } else {
                    for (tag in declaredTags.sorted()) {
                        val displayName = tagDisplayNames[tag] ?: tag
                        val treeCount = presetDetail.treeSelections[tag]?.size ?: 0
                        flow.children.add(Label("$displayName ($treeCount 树)").apply {
                            tooltip = Tooltip(tag)
                            style = "-fx-background-color: #e8f8f5; -fx-text-fill: #16a085; -fx-padding: 2 6 2 6; -fx-background-radius: 3; -fx-font-size: 11px;"
                        })
                    }
                }
                children.addAll(
                    Label("✅ 已声明生效用途:").apply {
                        style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #34495e;"
                    },
                    flow
                )
            }

            // 4. 禁用用途列表
            val disabledSection = VBox(4.0).apply {
                val flow = FlowPane(6.0, 6.0).apply { alignment = Pos.CENTER_LEFT }
                if (disabledTags.isEmpty()) {
                    flow.children.add(Label("无（全局用途全集均已在预设中声明）").apply {
                        style = "-fx-font-size: 11px; -fx-text-fill: #27ae60;"
                    })
                } else {
                    for (tag in disabledTags) {
                        val displayName = tagDisplayNames[tag] ?: tag
                        flow.children.add(Label(displayName).apply {
                            tooltip = Tooltip(tag)
                            style = "-fx-background-color: #fadbd8; -fx-text-fill: #c0392b; -fx-padding: 2 6 2 6; -fx-background-radius: 3; -fx-font-size: 11px;"
                        })
                    }
                }
                children.addAll(
                    Label("🚫 预设未声明而禁用的用途:").apply {
                        style = "-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #c0392b;"
                    },
                    flow
                )
            }

            // 5. 底部快捷通道
            val actionBox = HBox(10.0).apply {
                alignment = Pos.CENTER_RIGHT
                style = "-fx-padding: 8 0 0 0;"
                val btnJump = Button("跳转编辑此预设").apply {
                    style = "-fx-background-color: #3498db; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;"
                    setOnAction {
                        navigator.navigateTo(StrategyPresetExtension.TITLE, preset.id)
                        close()
                    }
                }
                children.add(btnJump)
            }

            children.addAll(infoCard, statsBox, declaredSection, disabledSection, actionBox)
        }

        dialogPane.content = ScrollPane(contentBox).apply {
            isFitToWidth = true
            style = "-fx-background-color: transparent;"
        }
    }
}
