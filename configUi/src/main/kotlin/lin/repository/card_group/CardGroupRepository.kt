package lin.repository.card_group

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class CardGroupRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val behaviorRepository: CardGroupBehaviorRepository
) {

    init {
        initSchema()
    }

    // ─────────────────────── DDL ───────────────────────────────────────────

    private fun initSchema() {
        // Manager 表：每条记录 = 一套分组方案。source_file 绑定在这里。
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS card_group_manager (
                id                      TEXT    PRIMARY KEY,
                name                    TEXT    NOT NULL,
                source_file             TEXT    NOT NULL,
                enabled                 INTEGER NOT NULL DEFAULT 1,
                manager_description     TEXT,
                manager_status          TEXT,
                default_include_derived INTEGER,
                preset_id               TEXT
            );
            """.trimIndent()
        )

        // Binding 表：属于某个 Manager，具体卡组划分。
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS card_group_binding (
                id              TEXT    PRIMARY KEY,
                manager_id      TEXT    NOT NULL,
                name            TEXT    NOT NULL,
                card_ids        TEXT    NOT NULL,
                description     TEXT,
                member_type     TEXT    NOT NULL DEFAULT 'STATIC',
                condition_id    TEXT,
                include_derived INTEGER
            );
            """.trimIndent()
        )
        // ⚠️ 存量库不会因 CREATE TABLE IF NOT EXISTS 而加列。
        //    旧库升级需手动执行 docs/sql/migrations/ 下的迁移脚本（用 sqlite3 CLI），
        //    不要在此处写自动 ALTER——迁移的时机与验证应留在人工可控的脚本里。
    }

    // ─────────────────────── Manager CRUD ──────────────────────────────────

    private val managerRowMapper = RowMapper { rs, _ ->
        CardManagerEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            sourceFile = rs.getString("source_file"),
            enabled = rs.getInt("enabled") == 1,
            description = rs.getString("manager_description"),
            status = rs.getString("manager_status"),
            defaultIncludeDerived = readNullableBoolean(rs, "default_include_derived"),
            presetId = rs.getString("preset_id")
        )
    }

    /**
     * 读可空布尔列。SQLite 无原生 BOOLEAN，存的是 INTEGER，
     * 直接用 `getObject(...) as? Boolean` 会拿到 Integer 而安全转换失败返回 null，
     * 把显式 false 误读成"未声明"——故统一走 getInt + wasNull。
     */
    private fun readNullableBoolean(rs: java.sql.ResultSet, column: String): Boolean? {
        val value = rs.getInt(column)
        return if (rs.wasNull()) null else value == 1
    }

    fun saveManager(entity: CardManagerEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO card_group_manager
                (id, name, source_file, enabled, manager_description, manager_status, default_include_derived)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name                    = excluded.name,
                source_file             = excluded.source_file,
                enabled                 = excluded.enabled,
                manager_description     = excluded.manager_description,
                manager_status          = excluded.manager_status,
                default_include_derived = excluded.default_include_derived
            """.trimIndent(),
            entity.id, entity.name, entity.sourceFile, if (entity.enabled) 1 else 0,
            entity.description, entity.status,
            entity.defaultIncludeDerived?.let { if (it) 1 else 0 }
        )
    }

    fun findAllManagers(): List<CardManagerEntity> =
        jdbcTemplate.query("SELECT * FROM card_group_manager", managerRowMapper)

    fun findManagers(onlyEnabled: Boolean = false): List<CardManagerEntity> {
        return if (onlyEnabled) {
            jdbcTemplate.query("SELECT * FROM card_group_manager WHERE enabled = 1", managerRowMapper)
        } else {
            findAllManagers()
        }
    }

    fun findManagerById(id: String): CardManagerEntity? =
        jdbcTemplate.query("SELECT * FROM card_group_manager WHERE id = ?", managerRowMapper, id).firstOrNull()

    /** 仅更新方案级元信息（description/status），不触碰 bindings（细粒度变更专用，避免整体 replaceBindings）。 */
    fun updateManagerMeta(id: String, description: String?, status: String?) {
        jdbcTemplate.update(
            "UPDATE card_group_manager SET manager_description = ?, manager_status = ? WHERE id = ?",
            description, status, id
        )
    }

    /**
     * T-TG-012：设置/清除卡组引用的用途预设（null = 不用预设，走全局用途规则）。
     *
     * **独立于 [saveManager]**（有意）：`saveManager` 是整体替换语义，若把 preset_id 纳入其
     * INSERT ... ON CONFLICT 列，则每次保存卡组都会把预设引用清空。
     */
    fun updateManagerPreset(id: String, presetId: String?) {
        jdbcTemplate.update("UPDATE card_group_manager SET preset_id = ? WHERE id = ?", presetId, id)
    }

    /** 按引用的策略预设 ID 查询卡组列表（用于反查预设引用者，避免全表扫）。 */
    fun findManagersByPresetId(presetId: String): List<CardManagerEntity> =
        jdbcTemplate.query(
            "SELECT * FROM card_group_manager WHERE preset_id = ? ORDER BY name COLLATE NOCASE ASC",
            managerRowMapper,
            presetId
        )

    fun deleteManager(id: String) {
        // 同时清理该 Manager 下的所有 Binding 及其行为
        behaviorRepository.deleteBehaviorsByManager(id)
        jdbcTemplate.update("DELETE FROM card_group_binding WHERE manager_id = ?", id)
        jdbcTemplate.update("DELETE FROM card_group_manager WHERE id = ?", id)
    }

    // ─────────────────────── Binding CRUD ──────────────────────────────────

    private val bindingRowMapper = RowMapper { rs, _ ->
        CardBindingEntity(
            id = rs.getString("id"),
            managerId = rs.getString("manager_id"),
            name = rs.getString("name"),
            cardIds = rs.getString("card_ids") ?: "[]",
            description = rs.getString("description"),
            memberType = rs.getString("member_type") ?: MemberType.STATIC,
            conditionId = rs.getString("condition_id"),
            includeDerived = readNullableBoolean(rs, "include_derived")
        )
    }

    fun saveBinding(entity: CardBindingEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO card_group_binding
                (id, manager_id, name, card_ids, description, member_type, condition_id, include_derived)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name            = excluded.name,
                card_ids        = excluded.card_ids,
                description     = excluded.description,
                member_type     = excluded.member_type,
                condition_id    = excluded.condition_id,
                include_derived = excluded.include_derived
            """.trimIndent(),
            entity.id, entity.managerId, entity.name, entity.cardIds,
            entity.description, entity.memberType, entity.conditionId,
            entity.includeDerived?.let { if (it) 1 else 0 }
        )
    }

    /** 替换某 Manager 下所有 Binding（先删后批量插） */
    fun replaceBindings(managerId: String, entities: List<CardBindingEntity>) {
        jdbcTemplate.update("DELETE FROM card_group_binding WHERE manager_id = ?", managerId)
        entities.forEach { saveBinding(it) }
    }

    fun findBindingsByManager(managerId: String): List<CardBindingEntity> =
        jdbcTemplate.query(
            "SELECT * FROM card_group_binding WHERE manager_id = ?",
            bindingRowMapper, managerId
        )

    fun deleteBinding(id: String) {
        behaviorRepository.deleteBehaviorsByBinding(id)
        jdbcTemplate.update(
            "DELETE FROM card_group_binding WHERE id = ?", id
        )
    }

    // ─────────────────────── 分组行为（委托 CardGroupBehaviorRepository）─────────────────────────────

    fun saveBehavior(entity: CardGroupBehaviorEntity) = behaviorRepository.saveBehavior(entity)
    fun findBehaviorsByBinding(bindingId: String): List<CardGroupBehaviorEntity> =
        behaviorRepository.findBehaviorsByBinding(bindingId)
    fun findBehaviorsByType(behaviorType: String): List<CardGroupBehaviorEntity> =
        behaviorRepository.findBehaviorsByType(behaviorType)
    fun deleteBehaviorsByBinding(bindingId: String) = behaviorRepository.deleteBehaviorsByBinding(bindingId)
    fun deleteBehavior(bindingId: String, behaviorType: String) =
        behaviorRepository.deleteBehavior(bindingId, behaviorType)
    fun findBehaviorsByManager(managerId: String, behaviorType: String): List<CardGroupBehaviorEntity> =
        behaviorRepository.findBehaviorsByManager(managerId, behaviorType)
    fun deleteBehaviorsByManager(managerId: String) =
        behaviorRepository.deleteBehaviorsByManager(managerId)
    fun deleteBehaviorsByManager(managerId: String, behaviorType: String) =
        behaviorRepository.deleteBehaviorsByManager(managerId, behaviorType)
    fun replaceBehaviors(bindingId: String, entities: List<CardGroupBehaviorEntity>) =
        behaviorRepository.replaceBehaviors(bindingId, entities)
}
