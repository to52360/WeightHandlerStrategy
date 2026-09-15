package lin.repository.condition_tree

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class ConditionTreeConfigRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS condition_tree_config (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                config_data TEXT NOT NULL,
                manager_id TEXT,
                inline_created INTEGER NOT NULL DEFAULT 0
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
    }

    fun save(entity: ConditionTreeConfigEntity) {
        val sql = """
            INSERT INTO condition_tree_config (id, name, config_data, manager_id, inline_created)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name = excluded.name,
                config_data = excluded.config_data,
                manager_id = excluded.manager_id,
                inline_created = excluded.inline_created
        """.trimIndent()
        jdbcTemplate.update(sql, entity.id, entity.name, entity.configData, entity.managerId, entity.inlineCreated)
    }

    fun findAll(): List<ConditionTreeConfigEntity> {
        return jdbcTemplate.query("SELECT * FROM condition_tree_config", rowMapper)
    }

    fun findById(id: String): ConditionTreeConfigEntity? {
        return jdbcTemplate.query("SELECT * FROM condition_tree_config WHERE id = ?", rowMapper, id).firstOrNull()
    }

    fun deleteById(id: String) {
        jdbcTemplate.update("DELETE FROM condition_tree_config WHERE id = ?", id)
    }

    /**
     * **卡组私有**条件树（`manager_id = ?`）——全局共享树（`manager_id IS NULL`）**不含**
     * （K-TG-014：全局资源不属于任何卡组，不随卡组删）。
     */
    fun findByManager(managerId: String): List<ConditionTreeConfigEntity> {
        val sql = "SELECT * FROM condition_tree_config WHERE manager_id = ?"
        return jdbcTemplate.query(sql, rowMapper, managerId)
    }

    /** 整类删除本卡组的私有条件树（级联删用；级联场景**豁免**引用校验，见 K-TG-014 专项 §6.0）。 */
    fun deleteByManager(managerId: String) {
        jdbcTemplate.update("DELETE FROM condition_tree_config WHERE manager_id = ?", managerId)
    }

    /**
     * 按卡组查询可用的条件树元数据：返回当前卡组私有条件树 + 全局共享条件树（manager_id 为 NULL）。
     * 与 tree_config（评估树）的 null 语义保持一致；写入端已将空字符串归一化为 NULL。
     * managerId 为空时等价全量返回。
     */
    fun findMetaByManagerId(managerId: String?): List<ConditionTreeMeta> {
        if (managerId.isNullOrEmpty()) {
            return findAllMeta()
        }
        val sql =
            "SELECT id, name, manager_id, inline_created FROM condition_tree_config WHERE manager_id = ? OR manager_id IS NULL"
        return jdbcTemplate.query(sql, { rs, _ ->
            rs.toMeta()
        }, managerId)
    }

    /** 全量返回条件树元数据（含 inlineCreated 标记）。 */
    fun findAllMeta(): List<ConditionTreeMeta> {
        return jdbcTemplate.query("SELECT id, name, manager_id, inline_created FROM condition_tree_config") { rs, _ ->
            rs.toMeta()
        }
    }

    private fun java.sql.ResultSet.toMeta(): ConditionTreeMeta = ConditionTreeMeta(
        id = getString("id"),
        name = getString("name"),
        managerId = getString("manager_id"),
        inlineCreated = getBoolean("inline_created")
    )

    private val rowMapper = RowMapper { rs, _ ->
        ConditionTreeConfigEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            configData = rs.getString("config_data"),
            managerId = rs.getString("manager_id"),
            inlineCreated = rs.getBoolean("inline_created")
        )
    }
}

/** 条件树元数据（id/name/managerId/inlineCreated），供 LIST 与背景知识过滤。 */
data class ConditionTreeMeta(
    val id: String,
    val name: String,
    val managerId: String? = null,
    val inlineCreated: Boolean = false
)
