package lin.ui.card_group.db

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
                id          TEXT    PRIMARY KEY,
                name        TEXT    NOT NULL,
                source_file TEXT    NOT NULL,
                enabled     INTEGER NOT NULL DEFAULT 1
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
                description     TEXT
            );
            """.trimIndent()
        )
    }

    // ─────────────────────── Manager CRUD ──────────────────────────────────

    private val managerRowMapper = RowMapper { rs, _ ->
        CardManagerEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            sourceFile = rs.getString("source_file"),
            enabled = rs.getInt("enabled") == 1
        )
    }

    fun saveManager(entity: CardManagerEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO card_group_manager (id, name, source_file, enabled)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name        = excluded.name,
                source_file = excluded.source_file,
                enabled     = excluded.enabled
            """.trimIndent(),
            entity.id, entity.name, entity.sourceFile, if (entity.enabled) 1 else 0
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
            cardIds = rs.getString("card_ids"),
            description = rs.getString("description")
        )
    }

    fun saveBinding(entity: CardBindingEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO card_group_binding (id, manager_id, name, card_ids, description)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name            = excluded.name,
                card_ids        = excluded.card_ids,
                description     = excluded.description
            """.trimIndent(),
            entity.id, entity.managerId, entity.name, entity.cardIds,
            entity.description
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
