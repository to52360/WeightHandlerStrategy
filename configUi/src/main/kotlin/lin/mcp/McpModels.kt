package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.victools.jsonschema.generator.OptionPreset
import com.github.victools.jsonschema.generator.SchemaGenerator
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import com.github.victools.jsonschema.generator.SchemaVersion
import com.github.victools.jsonschema.module.jackson.JacksonModule
import com.github.victools.jsonschema.module.jackson.JacksonOption
import lin.rule.tree.EvaluatorPayload
import lin.rule.tree.LogicNode
import lin.tree_config.bridge.defaultNodeName
import lin.ui.service.createTreeConfigMapper
import kotlin.reflect.full.memberProperties

object JsonSchemaUtils {
    private val generator: SchemaGenerator

    init {
        val configBuilder = SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(
                JacksonModule(
                    JacksonOption.RESPECT_JSONPROPERTY_REQUIRED,
                    JacksonOption.FLATTENED_ENUMS_FROM_JSONPROPERTY
                )
            )

        // 自动识别 Kotlin 的非空类型 (!isMarkedNullable)，将其设为 JSON Schema 的 required 必填字段
        configBuilder.forFields().withRequiredCheck { field ->
            try {
                val kClass = field.declaringType.erasedType.kotlin
                val kProp = kClass.memberProperties.find { it.name == field.name }
                kProp != null && !kProp.returnType.isMarkedNullable
            } catch (e: Exception) {
                false // 忽略反射失败的情况（如 Java 类）
            }
        }

        generator = SchemaGenerator(configBuilder.build())
    }

    fun generateSchemaJson(clazz: Class<*>): String {
        return generator.generateSchema(clazz).toString()
    }
}

/**
 * MCP tool 的纯数据结构。
 * 只有数据（name/description/schema）+ 一个 call 函数，没有抽象方法。
 * 具体 tool 用 data class 构造，不需要每个 tool 一个 class。
 */
data class McpToolHandler(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val call: (Map<String, Any?>) -> McpToolResult
)

data class McpToolResult(
    val contentJson: String,
    val isError: Boolean = false
)

val mcpMapper: ObjectMapper by lazy {
    createTreeConfigMapper()
}

/**
 * 显式响应工具函数：构造成功的 MCP 响应
 */
fun mcpSuccess(data: Any): McpToolResult =
    McpToolResult(mcpMapper.writeValueAsString(data), isError = false)

/**
 * 显式响应工具函数：构造失败的 MCP 业务错误响应
 */
fun mcpError(message: String): McpToolResult =
    McpToolResult(mcpMapper.writeValueAsString(mapOf("error" to message)), isError = true)

/**
 * MCP tool 输入参数校验失败的专用异常（如 action 非法、必填参数缺失）。
 * 在 [typedTool] 的 call lambda 内被统一捕获并转为 [mcpError] 业务错误响应，
 * 借此消除各 provider 中重复定义的仅承载错误消息的 ErrorAction sealed 子类型，
 * 让每个领域 sealed 只聚焦于真正的业务分支（List/Get/...）。
 */
class McpBadInput(message: String) : RuntimeException(message)

inline fun <reified I> normalizeRawArgs(rawArgs: Map<String, Any?>): Map<String, Any?> {
    val stringProps = try {
        (I::class as? kotlin.reflect.KClass<*>)?.memberProperties
            ?.filter { prop -> prop.returnType.classifier == String::class }
            ?.map { it.name }
            ?.toSet() ?: emptySet()
    } catch (_: Throwable) {
        emptySet()
    }

    val result = rawArgs.toMutableMap()
    for ((key, value) in rawArgs) {
        if (value is String && key !in stringProps) {
            val trimmed = value.trim()
            if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                try {
                    result[key] = mcpMapper.readValue(trimmed, Any::class.java)
                } catch (_: Throwable) {
                    // Ignore parse error, keep original value
                }
            }
        }
    }
    return result
}

/**
 * 声明式 typed tool 工厂：自动将 raw args Map 反序列化为 I，handler 只关心业务逻辑。
 * inputSchemaJson 由 victools 根据入参类型自动生成。
 */
inline fun <reified I> typedTool(
    name: String,
    description: String,
    crossinline handler: (I) -> McpToolResult
): McpToolHandler = McpToolHandler(
    name = name,
    description = description,
    inputSchemaJson = JsonSchemaUtils.generateSchemaJson(I::class.java),
    call = { rawArgs ->
        try {
            val normalizedArgs = normalizeRawArgs<I>(rawArgs)
            val typedInput = mcpMapper.convertValue(normalizedArgs, I::class.java)
            handler(typedInput)
        } catch (e: McpBadInput) {
            // 输入参数校验失败（action 非法 / 必填参数缺失）：直接转为业务错误响应
            mcpError(e.message ?: "bad input")
        } catch (e: IllegalArgumentException) {
            // 反序列化格式/类型错误
            mcpError("Input validation failed: ${e.message}")
        } catch (e: Throwable) {
            // 程序底层未捕获 Exception：记录日志并显式通知
            lin.myLog.error(e) { "MCP Tool [$name] 触发未捕获程序内部 Exception" }
            mcpError("Internal server error (${e.javaClass.simpleName}): ${e.message}")
        }
    }
)

/**
 * MCP tool 提供者接口。
 * 每个 domain 实现此接口，提供本域的 tool 列表。
 */
interface McpToolProvider {
    fun provide(): List<McpToolHandler>
}

data class SaveTreeTemplateInput(
    @field:com.fasterxml.jackson.annotation.JsonPropertyDescription("模板名称")
    val name: String,

    @field:com.fasterxml.jackson.annotation.JsonPropertyDescription("评估树骨架 JSON（字符串类型）。只存节点类型和引用关系，不存叶子节点的具体参数值。【必须传入「序列化后的 JSON 字符串」——即整段 JSON 文本整体作为一个字符串，不要直接传嵌套 JSON 对象】。")
    val contentJson: String,

    @field:com.fasterxml.jackson.annotation.JsonPropertyDescription("模板描述")
    val description: String? = null,

    @field:com.fasterxml.jackson.annotation.JsonPropertyDescription("模板分组 ID（来自 list_template_groups），可选")
    val groupId: String? = null
)

data class GetTreeRequest(
    @field:com.fasterxml.jackson.annotation.JsonPropertyDescription("要读取的模板或树配置的 ID")
    val id: String
)

data class DeleteTreeInput(
    @field:com.fasterxml.jackson.annotation.JsonPropertyDescription("要删除的评估树 ID，来自 list_evaluator_trees 返回的 id。删除不可恢复。")
    val treeId: String
)

/**
 * 评估树节点的表现层 DTO，携带人类可读的语义名称。
 * 用于 MCP tool 响应，让 AI 能理解树拓扑结构。
 */
data class NamedEvaluatorNode(
    val type: String,
    val name: String,
    val nodeId: String? = null,
    val children: List<NamedEvaluatorNode> = emptyList()
)

/**
 * 将核心领域模型 [LogicNode] 转换为携带语义命名的表现层 DTO。
 * 命名直接从 LogicNode 的 nodeName 提取，未指定时自动推导。
 */
fun LogicNode<EvaluatorPayload>.toNamed(): NamedEvaluatorNode {
    return buildNamed(this)
}

private fun buildNamed(
    node: LogicNode<EvaluatorPayload>
): NamedEvaluatorNode {
    val autoName = defaultNodeName(node) { payloadNodeId(it) }
    val children = buildChildren(node)
    val customName = node.nodeName

    return NamedEvaluatorNode(
        type = nodeTypeName(node),
        name = if (!customName.isNullOrBlank()) customName else autoName,
        nodeId = nodeIdOrNull(node),
        children = children
    )
}

private fun buildChildren(
    node: LogicNode<EvaluatorPayload>
): List<NamedEvaluatorNode> = when (node) {
    is LogicNode.And -> node.children.map { buildNamed(it) }
    is LogicNode.Or -> node.children.map { buildNamed(it) }
    is LogicNode.Not -> {
        listOf(buildNamed(node.child))
    }

    is LogicNode.Branch -> {
        listOf(buildNamed(node.onTrue), buildNamed(node.onFalse))
    }

    is LogicNode.Leaf -> emptyList()
}

private fun nodeTypeName(node: LogicNode<*>): String = when (node) {
    is LogicNode.And -> "AND"
    is LogicNode.Or -> "OR"
    is LogicNode.Not -> "NOT"
    is LogicNode.Branch -> "BRANCH"
    is LogicNode.Leaf -> "LEAF"
}

private fun nodeIdOrNull(node: LogicNode<EvaluatorPayload>): String? = when (node) {
    is LogicNode.Branch -> payloadNodeId(node.payload)
    is LogicNode.Leaf -> payloadNodeId(node.payload)
    else -> null
}

private fun payloadNodeId(payload: EvaluatorPayload): String = when (payload) {
    is EvaluatorPayload.Rule -> payload.nodeId
    is EvaluatorPayload.BranchCondition -> payload.nodeId
}
