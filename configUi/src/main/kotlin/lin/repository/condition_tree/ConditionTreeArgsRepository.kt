package lin.repository.condition_tree

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.jdbc.core.JdbcTemplate

private val argsMapper = jacksonObjectMapper()

/**
 * 条件树参数旁挂表（D-005）。
 *
 * 编码条件(ConditionRef)的参数不存条件树 JSON（条件树是纯结构模板），统一存此表（按树 id 一对一）。
 * args 为带 refId 前缀的 flat map JSON（如 {"hc.maxCost": 3}），消费方（评估树/排序/AuraBoost）
 * 只存树 id，编译时经 ConditionTreeArgsProvider 查此表注入。
 */
class ConditionTreeArgsRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS condition_tree_args (
                tree_id TEXT PRIMARY KEY,
                args_json TEXT NOT NULL
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    /** 保存/更新一棵树的参数（空 args 视为无参数，删除记录）。 */
    fun save(treeId: String, args: Map<String, Any>) {
        if (args.isEmpty()) {
            jdbcTemplate.update("DELETE FROM condition_tree_args WHERE tree_id = ?", treeId)
            return
        }
        val argsJson = argsMapper.writeValueAsString(args)
        val sql = """
            INSERT INTO condition_tree_args (tree_id, args_json)
            VALUES (?, ?)
            ON CONFLICT(tree_id) DO UPDATE SET args_json = excluded.args_json
        """.trimIndent()
        jdbcTemplate.update(sql, treeId, argsJson)
    }

    fun findById(treeId: String): Map<String, Any>? {
        val row = jdbcTemplate.query("SELECT args_json FROM condition_tree_args WHERE tree_id = ?") { rs, _ ->
            rs.getString("args_json")
        }.firstOrNull() ?: return null
        return try {
            argsMapper.readValue(row, Map::class.java) as? Map<String, Any>
        } catch (e: Exception) {
            emptyMap<String, Any>()
        }
    }

    fun deleteByTreeId(treeId: String) {
        jdbcTemplate.update("DELETE FROM condition_tree_args WHERE tree_id = ?", treeId)
    }
}
