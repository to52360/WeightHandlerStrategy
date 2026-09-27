package lin.mcp.action

import lin.config.PathConfig
import lin.mcp.*
import lin.repository.delete_snapshot.SnapshotStore

/**
 * 动作注册表（Q-007 / Q-TG-007）：从全部 McpToolProvider 的 actions 收集，按 resource 索引。
 * **一个 resource 一条 [ResourceActions]**；支持面用 `capability<T>()` 判定（能力＝类型，null = 不支持）。
 * get / list / delete / restore **四条链路共用本索引**（构建一次，按 resource 取用）。
 *
 * `providers` 用 [Lazy]：`getAll<McpToolProvider>()` 含 dispatcher 自身，构造期解析会循环依赖 StackOverflow
 * （2026-08-12 实测坑）⇒ 索引延迟到首次取用时构建；由 Koin 注册为单例，全进程只有这一份。
 * 新增资源动作 = 在 Provider 的 actions 中加一条，五处调用点（四 dispatcher + 恢复）零改动。
 */
class ActionRegistry(private val providers: Lazy<List<McpToolProvider>>) {

    private val byResource: Map<String, ResourceActions> by lazy {
        buildMap {
            providers.value.flatMap { it.actions }.forEach { entry ->
                // 值化后同一 resource 可能被多条声明贡献 ⇒ 重复会**静默覆盖能力**，故首次建索引即失败
                check(put(entry.resource, entry) == null) {
                    "重复声明 resource=${entry.resource}：同一 resource 只能有一条 ResourceActions（检查各 Provider 的 actions）"
                }
            }
        }
    }

    val resources: Set<String> get() = byResource.keys

    fun get(resource: String): GetCapability? = byResource[resource]?.capability()
    fun list(resource: String): ListCapability? = byResource[resource]?.capability()
    fun delete(resource: String): DeleteCapability? = byResource[resource]?.capability()
    fun restorable(resource: String): RestoreCapability? = byResource[resource]?.capability()

    fun getResources(): List<String> = supportResources { it.capability<GetCapability>() != null }
    fun listResources(): List<String> = supportResources { it.capability<ListCapability>() != null }
    fun deleteResources(): List<String> = supportResources { it.capability<DeleteCapability>() != null }

    private fun supportResources(predicate: (ResourceActions) -> Boolean): List<String> =
        byResource.values.filter(predicate).map { it.resource }
}

/**
 * get / list / delete / tool_capabilities 四个动作大类工具的 Dispatcher。
 * 共用同一个 [ActionRegistry] 单例（索引只建一次）；循环依赖由 registry 内部的 [Lazy] 处理。
 */
class GetDispatcher(
    private val registry: ActionRegistry
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<GetInput>(
            name = "get",
            description = "读取单个资源详情。resource 指定资源类型，id 为资源标识；支持面与各资源 id 语义先用 tool_capabilities 查询。"
        ) { input ->
            val capability = registry.get(input.resource)
            if (capability == null) {
                if (input.resource !in registry.resources) {
                    throw McpBadInput("未知 resource: ${input.resource}。支持: ${registry.resources}")
                }
                throw McpBadInput("resource=${input.resource} 不支持 get（当前支持 get: ${registry.getResources()}）")
            }
            if (input.id.isNullOrBlank()) {
                throw McpBadInput("get 需要 id 参数（resource=${input.resource} 的 id 语义用 tool_capabilities 查询）")
            }
            capability.handle(input.id)
        }
    )
}

class ListDispatcher(
    private val registry: ActionRegistry
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<ListInput>(
            name = "list",
            description = "列出资源列表。resource 指定资源类型，可选 managerId 按卡组过滤（支持面见 tool_capabilities）。"
        ) { input ->
            val capability = registry.list(input.resource)
            if (capability == null) {
                if (input.resource !in registry.resources) {
                    throw McpBadInput("未知 resource: ${input.resource}。支持: ${registry.resources}")
                }
                throw McpBadInput("resource=${input.resource} 不支持 list（当前支持 list: ${registry.listResources()}）")
            }
            capability.handle(input.managerId)
        }
    )
}

/**
 * delete 大类分发 + **删除编排单点**（T-TG-022 J′ 骨架）：
 * 收集者（registry）+ 分发者 + 机制持有者三合一 —— 各 Provider 只声明 [DeleteCapability] 的 `ops` 值，
 * 落快照 / 删除同事务 / 回显 `snapshotId` 在此写一次（原先 8 个 Action 各写一份）。
 * `ops` 是 Provider 构造期建好的**值**（无状态、捕获单例域服务）⇒ 本类无需缓存。
 */
class DeleteDispatcher(
    private val registry: ActionRegistry,
    /** 快照域唯一入口；机制细节不再泄漏到各 Provider。 */
    private val snapshotStore: SnapshotStore
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<DeleteInput>(
            name = "delete",
            description = "删除一个已保存的资源配置（删除前落快照，可经 restore_snapshot 恢复）。resource 指定资源类型，id 为资源标识；各资源的删除语义（引用校验 / 级联 / 快照范围）见 tool_capabilities 详情。"
        ) { input ->
            val capability = registry.delete(input.resource)
            if (capability == null) {
                if (input.resource !in registry.resources) {
                    throw McpBadInput("未知 resource: ${input.resource}。支持: ${registry.resources}")
                }
                throw McpBadInput("resource=${input.resource} 不支持 delete（当前支持 delete: ${registry.deleteResources()}）")
            }
            if (input.id.isNullOrBlank()) {
                throw McpBadInput("delete 需要 id 参数（resource=${input.resource} 的 id 语义用 tool_capabilities 查询）")
            }
            // 归一化单点：采集与删除拿到同一个 id（防"采集用 trimmed、删除用 raw"的静默错位）。
            val id = input.id.trim()
            // resource 路由标识只在这里出现一次（唯一来源 = ResourceActions.resource）；ops 是 Provider 建好的值
            mcpDeleteAction { snapshotStore.deleteWithSnapshot(input.resource, capability.ops, id) }
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
    private val registry: ActionRegistry
) : McpToolProvider {

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<CapabilitiesInput>(
            name = "tool_capabilities",
            description = "查询 get/list/delete 大工具的支持范围与字段语义（能力目录）。不传 resource 返回支持矩阵：get 支持哪些资源、list 支持哪些资源、delete 支持哪些资源。传 resource 返回该资源的完整操作视图：get/list/delete 各自的 id 参数语义、list 过滤字段、删除语义（引用校验/恢复快照/级联）。调用 get/list/delete 前不确定字段语义时先查本工具。【定库】不传 resource 时同时返回 databasePath（本进程连接的数据库绝对路径）与 cwd——动工前先查一次，确认写的是部署库还是源库（两库数据不同步）。"
        ) { input ->
            if (input.resource == null) {
                mcpSuccess(
                    mapOf(
                        "get" to registry.getResources(),
                        "list" to registry.listResources(),
                        "delete" to registry.deleteResources(),
                        // T-007：定库自证——DB 路径由 MCP 进程 cwd 决定（相对路径按 user.dir 解析），
                        // 调用方据此确认本轮写的是部署库还是源库，不必"写后再查"或靠 description 猜。
                        "databasePath" to PathConfig.databasePath.toAbsolutePath().normalize().toString(),
                        "cwd" to System.getProperty("user.dir"),
                        "hint" to "传 resource 可查看单个资源的操作详情（id 语义 / 过滤字段 / 删除语义）"
                    )
                )
            } else {
                val known = registry.get(input.resource)
                    ?: registry.list(input.resource)
                    ?: registry.delete(input.resource)
                    ?: throw McpBadInput("未知 resource: ${input.resource}。支持: ${registry.resources}")
                mcpSuccess(
                    mapOf(
                        "resource" to input.resource,
                        "get" to registry.get(input.resource)?.let { mapOf("id" to it.fieldHint) },
                        "list" to registry.list(input.resource)?.let {
                            mapOf(
                                "managerId" to if (it.supportsManagerIdFilter) "可选，按卡组过滤" else "不支持（忽略此字段）"
                            )
                        },
                        "delete" to registry.delete(input.resource)?.let {
                            mapOf("id" to it.fieldHint, "semantics" to it.semantics)
                        }
                    )
                )
            }
        }
    )
}
