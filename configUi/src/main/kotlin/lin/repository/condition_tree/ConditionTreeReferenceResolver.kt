package lin.repository.condition_tree

import com.fasterxml.jackson.databind.ObjectMapper
import lin.rule.condition.ConditionTreeConfig

/**
 * 条件树引用校验失败的领域异常（T-011 中立化）。
 *
 * 原实现抛 MCP 边界的 [McpBadInput]，随引用解析下沉到 repository 服务层后不可依赖 lin.mcp，
 * 故拆为领域异常；MCP 边界 [lin.mcp.McpModels.typedTool] 单独 catch 并以原消息转业务错误响应。
 */
class ConditionTreeReferenceException(message: String) : RuntimeException(message)

/**
 * 消费方条件树引用解析（Q-003 内联创建）：提供 treeJson 时自动创建条件树并返回新 id；
 * 否则按 conditionId 引用已有条件树（校验存在）。
 * 一次性树无需先 condition_tree(action=SAVE) 建模板，直接在消费方内联 JSON 一步创建。
 *
 * T-011：从 lin.mcp 迁至 repository 层中立 helper，供 AuraBoost / 谓词组 / conditionalStage
 * 等服务层保存编排复用（避免 repository 服务反向依赖 lin.mcp 的 McpBadInput）；
 * 校验失败抛 [ConditionTreeReferenceException]。
 *
 * @param managerId 消费方归属卡组：内联创建时写入树归属（null/空 = 全局共享树）。
 */
fun resolveConditionTreeReference(
    service: ConditionTreeConfigService,
    mapper: ObjectMapper,
    conditionId: String?,
    treeJson: String?,
    defaultName: String,
    label: String,
    managerId: String? = null
): String {
    val hasId = !conditionId.isNullOrBlank()
    val hasJson = !treeJson.isNullOrBlank()
    if (hasId && hasJson) {
        throw ConditionTreeReferenceException("$label：conditionId 与 treeJson 互斥，只能提供其一（复用已有树传 conditionId，一次性树传 treeJson 内联创建）")
    }
    if (hasJson) {
        val config = try {
            mapper.readValue(treeJson, ConditionTreeConfig::class.java)
        } catch (e: Exception) {
            throw ConditionTreeReferenceException(
                "$label 内联条件树 JSON 解析失败（需完整 {id,name,root} 结构，多态节点名用 AndNode/OrNode/NotNode/BranchNode/Leaf）: ${e.message}"
            )
        }
        return service.saveConfig(config.name ?: defaultName, config, managerId = managerId, inlineCreated = true)
    }
    if (!hasId) {
        throw ConditionTreeReferenceException("$label：conditionId 与 treeJson 必须提供其一（一次性树传 treeJson 内联创建，无需先建模板）")
    }
    if (service.findById(conditionId) == null) {
        throw ConditionTreeReferenceException("$label 条件树不存在: $conditionId（一次性树可直接传 treeJson 内联创建，或先 save_condition_tree 建模板）")
    }
    return conditionId
}
