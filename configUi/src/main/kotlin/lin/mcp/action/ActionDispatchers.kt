package lin.mcp.action

import lin.mcp.*

/**
 * 动作注册表（Q-007）：从全部 McpToolProvider 集合的 actions 收集（无独立 ResourceActionProvider 接口），
 * 按 resource 索引；动作支持面用 as? 判断实现的是哪个子接口（GetAction / ListAction / DeleteAction）。
 * 新增资源动作 = 在 Provider 的 actions 中加一项，四类 dispatcher 零改动。
 */
internal class ActionRegistry(providers: List<McpToolProvider>) {
    private val byResource: Map<String, ResourceAction> =
        providers.flatMap { it.actions }.associateBy { it.resource }

    val resources: Set<String> get() = byResource.keys

    fun get(resource: String): GetAction? = byResource[resource] as? GetAction
    fun list(resource: String): ListAction? = byResource[resource] as? ListAction
    fun delete(resource: String): DeleteAction? = byResource[resource] as? DeleteAction
    fun getResources(): List<String> = byResource.values.filterIsInstance<GetAction>().map { it.resource }
    fun listResources(): List<String> = byResource.values.filterIsInstance<ListAction>().map { it.resource }
    fun deleteResources(): List<String> = byResource.values.filterIsInstance<DeleteAction>().map { it.resource }
}

/**
 * get / list / delete / tool_capabilities 四个动作大类工具的 Dispatcher。
 * 注入用 Lazy：getAll<McpToolProvider>() 包含 dispatcher 自身，若在构造期解析会触发循环依赖 StackOverflow
 * （2026-08-12 实测坑），故延迟到首次 provide() 时解析。
 */
class GetDispatcher(
    private val providers: Lazy<List<McpToolProvider>>
) : McpToolProvider {

    private val registry: ActionRegistry by lazy { ActionRegistry(providers.value) }

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<GetInput>(
            name = "get",
            description = "读取单个资源详情。resource 指定资源类型（evaluator_tree / combo_plan / card_group / card_pool / condition_tree / aura_boost / purpose_tag / tree_template / draft），id 为资源标识。id 语义因资源而异（card_pool=fileName、card_group=managerId、purpose_tag=tagId、draft=draftId、其余=资源 id），不确定时先调 tool_capabilities 查询。"
        ) { input ->
            val action = registry.get(input.resource)
            if (action == null) {
                if (input.resource !in registry.resources) {
                    throw McpBadInput("未知 resource: ${input.resource}。支持: ${registry.resources}")
                }
                throw McpBadInput("resource=${input.resource} 不支持 get（当前支持 get: ${registry.getResources()}）")
            }
            if (input.id.isNullOrBlank()) {
                throw McpBadInput("get 需要 id 参数（resource=${input.resource} 的 id 语义用 tool_capabilities 查询）")
            }
            action.handleGet(input.id)
        }
    )
}

class ListDispatcher(
    private val providers: Lazy<List<McpToolProvider>>
) : McpToolProvider {

    private val registry: ActionRegistry by lazy { ActionRegistry(providers.value) }

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<ListInput>(
            name = "list",
            description = "列出资源列表。resource 指定资源类型（evaluator_tree / combo_plan / card_group / card_pool / condition_tree / aura_boost / purpose_tag / tree_template / capability_background），可选 managerId 按卡组过滤（仅部分资源支持）。不确定支持面时先调 tool_capabilities 查询。"
        ) { input ->
            val action = registry.list(input.resource)
            if (action == null) {
                if (input.resource !in registry.resources) {
                    throw McpBadInput("未知 resource: ${input.resource}。支持: ${registry.resources}")
                }
                throw McpBadInput("resource=${input.resource} 不支持 list（当前支持 list: ${registry.listResources()}）")
            }
            action.handleList(input.managerId)
        }
    )
}

class DeleteDispatcher(
    private val providers: Lazy<List<McpToolProvider>>
) : McpToolProvider {

    private val registry: ActionRegistry by lazy { ActionRegistry(providers.value) }

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<DeleteInput>(
            name = "delete",
            description = "删除一个已保存的资源配置。resource 指定资源类型（evaluator_tree / combo_plan / card_group / card_pool / condition_tree / aura_boost），id 为资源标识（card_pool=fileName、card_group=managerId、其余=资源 id）。删除语义（引用校验/恢复快照/级联）因资源而异，详见 tool_capabilities 详情。"
        ) { input ->
            val action = registry.delete(input.resource)
            if (action == null) {
                if (input.resource !in registry.resources) {
                    throw McpBadInput("未知 resource: ${input.resource}。支持: ${registry.resources}")
                }
                throw McpBadInput("resource=${input.resource} 不支持 delete（当前支持 delete: ${registry.deleteResources()}）")
            }
            if (input.id.isNullOrBlank()) {
                throw McpBadInput("delete 需要 id 参数（resource=${input.resource} 的 id 语义用 tool_capabilities 查询）")
            }
            action.handleDelete(input.id)
        }
    )
}

/**
 * 能力查询工具（D-007）：两级查询。
 * 一级：不传 resource → get/list/delete 各支持哪些 resource 的矩阵。
 * 二级：传 resource → 该资源的操作详情（get/list/delete 三动作各自的参数语义 + 删除语义 note）。
 * 全部由 Provider 注册的 actions 动态生成（零硬编码资源列表）。
 */
class ToolCapabilitiesProvider(
    private val providers: Lazy<List<McpToolProvider>>
) : McpToolProvider {

    private val registry: ActionRegistry by lazy { ActionRegistry(providers.value) }

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<CapabilitiesInput>(
            name = "tool_capabilities",
            description = "查询 get/list/delete 大工具的支持范围与字段语义（能力目录）。不传 resource 返回支持矩阵：get 支持哪些资源、list 支持哪些资源、delete 支持哪些资源。传 resource 返回该资源的完整操作视图：get/list/delete 各自的 id 参数语义、list 过滤字段、删除语义（引用校验/恢复快照/级联）。调用 get/list/delete 前不确定字段语义时先查本工具。"
        ) { input ->
            if (input.resource == null) {
                mcpSuccess(
                    mapOf(
                        "get" to registry.getResources(),
                        "list" to registry.listResources(),
                        "delete" to registry.deleteResources(),
                        "hint" to "传 resource 可查看单个资源的操作详情（id 语义 / 过滤字段 / 删除语义）"
                    )
                )
            } else {
                val action = registry.get(input.resource)
                    ?: registry.list(input.resource)
                    ?: registry.delete(input.resource)
                    ?: throw McpBadInput("未知 resource: ${input.resource}。支持: ${registry.resources}")
                mcpSuccess(
                    mapOf(
                        "resource" to input.resource,
                        "get" to registry.get(input.resource)?.let { mapOf("id" to it.getFieldHint) },
                        "list" to registry.list(input.resource)?.let {
                            mapOf(
                                "managerId" to if (it.supportsManagerIdFilter) "可选，按卡组过滤" else "不支持（忽略此字段）"
                            )
                        },
                        "delete" to registry.delete(input.resource)?.let {
                            mapOf("id" to it.deleteFieldHint, "semantics" to it.deleteSemantics)
                        }
                    )
                )
            }
        }
    )
}
