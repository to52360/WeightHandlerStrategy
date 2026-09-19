package lin.ui.condition_tree.components

import javafx.scene.control.Alert
import lin.repository.condition_tree.ConditionTreeConfigRepository
import lin.ui.components.action.ResourcePickerBar
import lin.ui.components.action.UiCapabilities
import lin.ui.components.action.UiCapability
import lin.ui.condition_tree.ConditionTreeDialog
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * 条件树下拉项数据封装（纯领域模型）
 */
data class ConditionTreeOption(
    val id: String,
    val name: String
) {
    override fun toString(): String {
        return if (id.isEmpty()) "(未选择条件树)" else name.ifBlank { "[$id] (未命名)" }
    }
}

/**
 * 条件树领域能力值套件工厂：
 * 生成纯数据能力值列表（UiCapability<ConditionTreeOption>），闭环弹窗与库查预览
 */
object ConditionTreeCapabilities : KoinComponent {

    private val defaultRepo: ConditionTreeConfigRepository by inject()

    /**
     * 构造默认三件套能力值：新建 + 编辑 + 预览
     */
    fun defaultSet(
        managerIdProvider: () -> String?,
        onRefreshRequested: () -> Unit
    ): List<UiCapability<ConditionTreeOption>> = listOf(
        UiCapabilities.create { ctx ->
            val dialog = ConditionTreeDialog(autoCreateDraft = true, managerId = managerIdProvider())
            dialog.showAndWait().ifPresent { createdId ->
                onRefreshRequested()
                ctx.select(ConditionTreeOption(createdId, "新建条件树 ($createdId)"))
            }
        },
        UiCapabilities.edit { item, ctx ->
            val dialog = ConditionTreeDialog(initialSelectTreeId = item.id, managerId = managerIdProvider())
            dialog.showAndWait().ifPresent { editedId ->
                onRefreshRequested()
                ctx.select(ConditionTreeOption(editedId ?: item.id, item.name))
            }
        },
        UiCapabilities.inspect { item, _ ->
            val entity = defaultRepo.findById(item.id)
            if (entity == null) {
                Alert(Alert.AlertType.ERROR, "未在数据库中找到 ID 为 [${item.id}] 的条件树配置。").showAndWait()
            } else {
                val content =
                    "【条件树 ID】: ${entity.id}\n【条件树名称】: ${entity.name}\n【关联卡组 ID】: ${entity.managerId ?: "(全局共享)"}\n\n【节点配置 JSON 摘要】:\n${entity.configData}"
                Alert(Alert.AlertType.INFORMATION, content).apply {
                    title = "条件树预览"
                    headerText = "条件树配置摘要详情"
                }.showAndWait()
            }
        }
    )
}

/**
 * 便捷扩展：读取当前选中的条件树 ID
 */
val ResourcePickerBar<ConditionTreeOption>.selectedTreeId: String?
    get() = selectedItem?.id?.takeIf { it.isNotEmpty() }

/**
 * 便捷扩展：按 ID 查找并选中条件树（支持未知外部 ID 优雅占位）
 */
fun ResourcePickerBar<ConditionTreeOption>.selectTreeId(treeId: String?) {
    if (treeId.isNullOrEmpty()) {
        clearSelection()
        return
    }
    val matched = comboBox.items.find { it.id == treeId }
    if (matched != null) {
        selectedItem = matched
    } else {
        val placeholder = ConditionTreeOption(treeId, "外部/自定义条件树 ($treeId)")
        comboBox.items.add(placeholder)
        selectedItem = placeholder
    }
}
