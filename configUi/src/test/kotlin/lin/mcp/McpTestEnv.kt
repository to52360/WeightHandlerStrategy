package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import lin.moduls.loadMcpModules
import org.koin.core.context.GlobalContext
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.Path

/**
 * MCP 测试公共环境基类。
 *
 * 封装 DB 配置、Koin 加载、工具注册、call() 包装、DB/文件清理等可复用能力。
 * 新增测试文件继承此类即可直接使用 [call] / [cleanup]，无需重复编写环境搭建代码。
 */
abstract class McpTestEnv {

    companion object {
        private var initialized = false

        /** 已注册的全部 MCP tool handler，按 name 索引 */
        lateinit var tools: Map<String, McpToolHandler>
            private set

        /** 带 pretty-print 的 ObjectMapper */
        val mapper: ObjectMapper = ObjectMapper().apply { enable(SerializationFeature.INDENT_OUTPUT) }

        @JvmStatic
        @org.junit.BeforeClass
        fun setupEnv() {
            if (initialized) return
            initialized = true
            System.setProperty("hs_cards.db.path", "../hs_cards.db")
            System.setProperty("database.path", "../weightHandlerStrategy.db")
            System.setProperty("cardgroup.dir.path", "../data/cardgroup")
            loadMcpModules()
            val providers = GlobalContext.get().getAll<McpToolProvider>()
            tools = providers.flatMap { it.provide() }.associateBy { it.name }
            println(">>> loaded tools: ${tools.keys.joinToString()}")
        }
    }

    // ── 每个测试实例独立的资源追踪 ──

    /** 本次运行产生的 cardgroup 文件路径 */
    protected var savedFile: Path? = null

    /** 本次创建的 card group manager ID */
    protected var managerId: String? = null

    /** 本次创建的 binding ID 列表 */
    protected var bindingIds: List<String> = emptyList()

    /** 本次创建的 tree config ID */
    protected var treeId: String? = null

    /** 本次创建的 coded tree config ID */
    protected var codedTreeId: String? = null

    // ── 公共方法 ──

    /**
     * 调用指定名称的 MCP tool，传入 JSON string 参数。
     * 自动 pretty-print 响应到 stdout。
     */
    fun call(name: String, json: String = "{}"): McpToolResult {
        val handler = tools[name] ?: error("tool not found: $name (available: ${tools.keys})")
        val args = mapper.readValue(json, Map::class.java) as Map<String, Any?>
        return try {
            val result = handler.call(args)
            val pretty = runCatching {
                mapper.writeValueAsString(mapper.readValue(result.contentJson, Any::class.java))
            }.getOrElse { result.contentJson }
            println("===== $name (isError=${result.isError}) =====")
            println(pretty)
            result
        } catch (e: Throwable) {
            println("===== $name THREW =====")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * 清理本次测试产生的 DB 记录和文件。
     * 应在 @Test 方法的 finally 块中调用。
     */
    fun cleanup() {
        runCatching {
            val jdbc = GlobalContext.get().get<JdbcTemplate>()
            treeId?.let {
                jdbc.update("DELETE FROM evaluator_leaf_config WHERE config_id = ?", it)
                jdbc.update("DELETE FROM tree_config WHERE id = ?", it)
            }
            codedTreeId?.let {
                jdbc.update("DELETE FROM evaluator_leaf_config WHERE config_id = ?", it)
                jdbc.update("DELETE FROM tree_config WHERE id = ?", it)
            }
            managerId?.let {
                jdbc.update("DELETE FROM card_group_binding WHERE manager_id = ?", it)
                jdbc.update("DELETE FROM card_group_manager WHERE id = ?", it)
            }
            savedFile?.let { Files.deleteIfExists(it) }
            println(">>> cleanup done (treeId=$treeId, codedTreeId=$codedTreeId, managerId=$managerId, savedFile=$savedFile)")
        }.onFailure { e -> println(">>> cleanup failed: ${e.message}") }
    }
}
