package lin.ui.card_group.components

import javafx.scene.control.Alert
import lin.rule.tree.CardGroupManagerConfig
import lin.rule.tree.GroupMembership
import lin.ui.components.action.UiCapabilities
import lin.ui.components.action.UiCapability

/**
 * 卡组方案领域能力值套件工厂
 */
object CardGroupCapabilities {

    /**
     * 构造默认卡组能力套件：查看卡组详情 + 前往编辑
     */
    fun defaultSet(
        onNavigateToManager: ((CardGroupManagerConfig) -> Unit)? = null
    ): List<UiCapability<CardGroupManagerConfig>> = listOf(
        UiCapabilities.inspect("详情") { manager, _ ->
            val staticCount = manager.bindings.count { it.membership is GroupMembership.Static }
            val predicateCount = manager.bindings.count { it.membership is GroupMembership.Predicate }
            val derivedDesc = when (manager.defaultIncludeDerived) {
                true -> "开启 (卡组级默认)"
                false -> "关闭 (卡组级默认)"
                null -> "未声明 (回落关闭)"
            }

            val bindingSummaries = if (manager.bindings.isEmpty()) {
                "  (暂无配置任何分组)"
            } else {
                manager.bindings.joinToString("\n") { b ->
                    val typeDesc = when (val m = b.membership) {
                        is GroupMembership.Static -> "静态组 [${m.cardIds.size} 张卡]"
                        is GroupMembership.Predicate -> "谓词组 [条件树: ${m.conditionId}]"
                    }
                    val behaviorCount = b.behaviors.size
                    "• ${b.name} — $typeDesc (行为覆盖: ${behaviorCount}条)"
                }
            }

            val content = buildString {
                appendLine("【方案 ID】: ${manager.cardGroupManagerId}")
                appendLine("【方案名称】: ${manager.name}")
                appendLine("【运行状态】: ${if (manager.enabled) "已启用" else "未启用"}")
                appendLine("【衍生卡纳入】: $derivedDesc")
                appendLine("【分组概览】: 共 ${manager.bindings.size} 个分组 (静态组: $staticCount, 谓词组: $predicateCount)")
                appendLine()
                appendLine("【分组明细】:")
                appendLine(bindingSummaries)
            }

            Alert(Alert.AlertType.INFORMATION, content).apply {
                title = "卡组方案详情"
                headerText = "卡组方案 [${manager.name}] 全景概览"
                dialogPane.prefWidth = 480.0
            }.showAndWait()
        },
        UiCapabilities.edit("前往编辑") { manager, _ ->
            onNavigateToManager?.invoke(manager)
        }
    )
}
