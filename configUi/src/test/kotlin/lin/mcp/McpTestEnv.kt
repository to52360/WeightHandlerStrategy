package lin.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import lin.moduls.loadMcpModules
import org.koin.core.context.GlobalContext
import org.springframework.jdbc.core.JdbcTemplate
import java.io.File
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

        /** cardgroup 文件目录 */
        private val cardgroupDir: String by lazy { System.getProperty("cardgroup.dir.path", "../data/cardgroup") }

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

        /**
         * 批量清理指定演练产生的 DB 数据（manager + bindings + trees），**不删 cardgroup 文件**。
         * 在重新分组时调用，保留 Stage 1 生成的卡池文件。
         *
         * @param fileName cardgroup 文件名（不含扩展名），如 "real_libram_deck"
         * @param managerName card group manager 名称，如 "real_libram_groups"
         * @param deleteFile 是否同时删除 cardgroup 文件，默认 false（保留卡池）
         */
        @JvmStatic
        fun cleanupAll(fileName: String, managerName: String, deleteFile: Boolean = false) {
            println(">>> ===== 清理旧数据 ($fileName / $managerName, deleteFile=$deleteFile) =====")
            var cleaned = 0

            // 1. 可选：删除 cardgroup 文件
            if (deleteFile) {
                val cardgroupPath = Path.of(cardgroupDir, "$fileName.cardgroup")
                if (Files.exists(cardgroupPath)) {
                    Files.delete(cardgroupPath)
                    cleaned++
                    println("   删除文件: $cardgroupPath")
                }
                // 删除 .draft 草稿文件
                try {
                    Files.list(Path.of(cardgroupDir)).filter {
                        it.fileName.toString().startsWith("$fileName.") || it.fileName.toString().startsWith("${fileName}_")
                    }.forEach {
                        Files.delete(it)
                        cleaned++
                        println("   删除文件: $it")
                    }
                } catch (_: Exception) {}
            }

            // 2. 删除 DB 中的 manager + bindings + trees
            runCatching {
                val jdbc = GlobalContext.get().get<JdbcTemplate>()
                val managers = jdbc.queryForList(
                    "SELECT id FROM card_group_manager WHERE name = ?", managerName
                )
                for (row in managers) {
                    val mgrId = row["id"] as String
                    // 先删该 manager 下的树
                    val trees = jdbc.queryForList(
                        "SELECT id FROM tree_config WHERE manager_id = ?", mgrId
                    )
                    for (tree in trees) {
                        val tid = tree["id"] as String
                        jdbc.update("DELETE FROM evaluator_leaf_config WHERE config_id = ?", tid)
                        jdbc.update("DELETE FROM tree_config WHERE id = ?", tid)
                        cleaned++
                        println("   删除 tree_config: $tid")
                    }
                    jdbc.update("DELETE FROM card_group_binding WHERE manager_id = ?", mgrId)
                    jdbc.update("DELETE FROM card_group_manager WHERE id = ?", mgrId)
                    cleaned++
                    println("   删除 card_group_manager: $mgrId")
                }
                if (managers.isEmpty()) println("   DB 无匹配 manager 记录")
            }.onFailure { e -> println("   DB 清理失败: ${e.message}") }

            // 3. 额外：按文件名模糊清理可能残留的 manager
            runCatching {
                val jdbc = GlobalContext.get().get<JdbcTemplate>()
                val orphanManagers = jdbc.queryForList(
                    "SELECT id, name FROM card_group_manager WHERE source_file = ?", "$fileName.cardgroup"
                )
                for (row in orphanManagers) {
                    val mgrId = row["id"] as String
                    val trees = jdbc.queryForList("SELECT id FROM tree_config WHERE manager_id = ?", mgrId)
                    for (tree in trees) {
                        val tid = tree["id"] as String
                        jdbc.update("DELETE FROM evaluator_leaf_config WHERE config_id = ?", tid)
                        jdbc.update("DELETE FROM tree_config WHERE id = ?", tid)
                    }
                    jdbc.update("DELETE FROM card_group_binding WHERE manager_id = ?", mgrId)
                    jdbc.update("DELETE FROM card_group_manager WHERE id = ?", mgrId)
                    cleaned++
                    println("   删除孤儿 manager: ${row["name"]} ($mgrId)")
                }
            }.onFailure { e -> println("   孤儿 manager 清理失败: ${e.message}") }

            println(">>> 清理完毕，共清理 $cleaned 项")
        }

        /**
         * 保存本轮实战演练追踪到的 ID 到文件，便于后续快速清理。
         */
        @JvmStatic
        fun saveTrackedIds(fileName: String, managerName: String, managerId: String?, treeIds: List<String>) {
            val dir = File(cardgroupDir, ".tracked")
            dir.mkdirs()
            val file = File(dir, "$fileName.json")
            val json = mapper.writeValueAsString(mapOf(
                "fileName" to fileName,
                "managerName" to managerName,
                "managerId" to (managerId ?: ""),
                "treeIds" to treeIds,
                "timestamp" to System.currentTimeMillis()
            ))
            file.writeText(json)
            println(">>> ID 追踪已保存: ${file.absolutePath}")
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

    /** 本次创建的所有 tree config ID 列表（用于实战演练追踪） */
    protected val allTreeIds: MutableList<String> = mutableListOf()

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
