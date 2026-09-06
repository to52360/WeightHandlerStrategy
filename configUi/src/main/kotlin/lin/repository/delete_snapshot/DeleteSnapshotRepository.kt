package lin.repository.delete_snapshot

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/**
 * delete_snapshot 表 CRUD（T-010：delete 快照落库）。
 * 一个 delete 操作 = 一条记录；card_group 级联删多个实体（manager+bindings+trees）整体存一条 payload。
 */
class DeleteSnapshotRepository(private val jdbcTemplate: JdbcTemplate) {
    init {
        initSchema()
    }

    private fun initSchema() {
        val sql = """
            CREATE TABLE IF NOT EXISTS delete_snapshot (
                snapshot_id  TEXT PRIMARY KEY,
                resource     TEXT NOT NULL,
                entity_id    TEXT NOT NULL,
                entity_name  TEXT,
                payload      TEXT NOT NULL,
                created_at   TEXT NOT NULL
            );
        """.trimIndent()
        jdbcTemplate.execute(sql)
        jdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_delete_snapshot_resource ON delete_snapshot(resource)"
        )
    }

    private val rowMapper = RowMapper { rs, _ ->
        DeleteSnapshotEntity(
            snapshotId = rs.getString("snapshot_id"),
            resource = rs.getString("resource"),
            entityId = rs.getString("entity_id"),
            entityName = rs.getString("entity_name"),
            payload = rs.getString("payload"),
            createdAt = rs.getString("created_at")
        )
    }

    fun save(entity: DeleteSnapshotEntity) {
        val sql = """
            INSERT INTO delete_snapshot (snapshot_id, resource, entity_id, entity_name, payload, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()
        jdbcTemplate.update(
            sql,
            entity.snapshotId, entity.resource, entity.entityId, entity.entityName, entity.payload, entity.createdAt
        )
    }

    /** 按 created_at 倒序返回全部快照（最新在前）。 */
    fun findAll(): List<DeleteSnapshotEntity> =
        jdbcTemplate.query("SELECT * FROM delete_snapshot ORDER BY created_at DESC", rowMapper)

    fun findById(snapshotId: String): DeleteSnapshotEntity? =
        jdbcTemplate.query(
            "SELECT * FROM delete_snapshot WHERE snapshot_id = ?", rowMapper, snapshotId
        ).firstOrNull()

    fun deleteById(snapshotId: String) {
        jdbcTemplate.update("DELETE FROM delete_snapshot WHERE snapshot_id = ?", snapshotId)
    }

    /** 仅保留最新 n 条（按 created_at 倒序），供采集后自动清理防快照无限膨胀。 */
    fun trimTo(n: Int) {
        val sql = """
            DELETE FROM delete_snapshot
            WHERE snapshot_id NOT IN (
                SELECT snapshot_id FROM delete_snapshot ORDER BY created_at DESC LIMIT ?
            )
        """.trimIndent()
        jdbcTemplate.update(sql, n)
    }
}
