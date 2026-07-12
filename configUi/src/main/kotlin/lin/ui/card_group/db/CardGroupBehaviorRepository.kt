package lin.ui.card_group.db

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/**
 * 分组行为关联表（card_group_behavior）的数据访问。
 * 每行一种分组级行为（OVERRIDE / USE_ACTION / 未来扩展），(binding_id, behavior_type) 为主键。
 * 与 Manager/Binding 主表分离，新增行为类型只加行、不改主表结构。
 */
class CardGroupBehaviorRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS card_group_behavior (
                binding_id      TEXT    NOT NULL,
                behavior_type   TEXT    NOT NULL,
                payload         TEXT    NOT NULL,
                PRIMARY KEY (binding_id, behavior_type)
            );
            """.trimIndent()
        )
    }

    private val behaviorRowMapper = RowMapper { rs, _ ->
        CardGroupBehaviorEntity(
            bindingId = rs.getString("binding_id"),
            behaviorType = rs.getString("behavior_type"),
            payload = rs.getString("payload")
        )
    }

    fun saveBehavior(entity: CardGroupBehaviorEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO card_group_behavior (binding_id, behavior_type, payload)
            VALUES (?, ?, ?)
            ON CONFLICT(binding_id, behavior_type) DO UPDATE SET
                payload = excluded.payload
            """.trimIndent(),
            entity.bindingId, entity.behaviorType, entity.payload
        )
    }

    fun findBehaviorsByBinding(bindingId: String): List<CardGroupBehaviorEntity> =
        jdbcTemplate.query(
            "SELECT * FROM card_group_behavior WHERE binding_id = ?",
            behaviorRowMapper, bindingId
        )

    fun findBehaviorsByType(behaviorType: String): List<CardGroupBehaviorEntity> =
        jdbcTemplate.query(
            "SELECT * FROM card_group_behavior WHERE behavior_type = ?",
            behaviorRowMapper, behaviorType
        )

    fun deleteBehaviorsByBinding(bindingId: String) {
        jdbcTemplate.update("DELETE FROM card_group_behavior WHERE binding_id = ?", bindingId)
    }

    fun deleteBehavior(bindingId: String, behaviorType: String) {
        jdbcTemplate.update(
            "DELETE FROM card_group_behavior WHERE binding_id = ? AND behavior_type = ?",
            bindingId, behaviorType
        )
    }

    fun deleteBehaviorsByManager(managerId: String) {
        jdbcTemplate.update(
            "DELETE FROM card_group_behavior WHERE binding_id IN (SELECT id FROM card_group_binding WHERE manager_id = ?)",
            managerId
        )
    }

    /** 按 Manager 筛选某类型的行为行（JOIN 其所属 Binding） */
    fun findBehaviorsByManager(managerId: String, behaviorType: String): List<CardGroupBehaviorEntity> =
        jdbcTemplate.query(
            """
            SELECT b.binding_id, b.behavior_type, b.payload
            FROM card_group_behavior b
            JOIN card_group_binding cgb ON cgb.id = b.binding_id
            WHERE cgb.manager_id = ? AND b.behavior_type = ?
            """.trimIndent(),
            behaviorRowMapper, managerId, behaviorType
        )

    /** 清理某 Manager 下特定类型的行为行（保留其他类型，如 USE_ACTION） */
    fun deleteBehaviorsByManager(managerId: String, behaviorType: String) {
        jdbcTemplate.update(
            """
            DELETE FROM card_group_behavior
            WHERE behavior_type = ?
              AND binding_id IN (SELECT id FROM card_group_binding WHERE manager_id = ?)
            """.trimIndent(),
            behaviorType, managerId
        )
    }

    /** 替换某 Binding 下所有行为（先删后批量插） */
    fun replaceBehaviors(bindingId: String, entities: List<CardGroupBehaviorEntity>) {
        deleteBehaviorsByBinding(bindingId)
        entities.forEach { saveBehavior(it) }
    }
}
