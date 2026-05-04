package lin.card_group.repository

import lin.card_group.domain.CardBindingEntity
import lin.card_group.domain.CardManagerEntity
import lin.utils.database.SqliteJdbcProvider
import org.springframework.jdbc.core.RowMapper

class CardGroupRepository(private val jdbcProvider: SqliteJdbcProvider) {

    init {
        initSchema()
    }

    // ─────────────────────── DDL ───────────────────────────────────────────

    private fun initSchema() {
        // Manager 表：每条记录 = 一套分组方案
        jdbcProvider.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS card_group_manager (
                id      TEXT    PRIMARY KEY,
                name    TEXT    NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1
            );
            """.trimIndent()
        )

        // Binding 表：使用 id 作为主键
        jdbcProvider.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS card_group_binding (
                id          TEXT    PRIMARY KEY,
                manger_id   TEXT    NOT NULL,
                source_file TEXT    NOT NULL,
                name        TEXT    NOT NULL,
                card_ids    TEXT    NOT NULL
            );
            """.trimIndent()
        )
    }

    // ─────────────────────── Manager CRUD ──────────────────────────────────

    private val managerRowMapper = RowMapper { rs, _ ->
        CardManagerEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            enabled = rs.getInt("enabled") == 1
        )
    }

    fun saveManager(entity: CardManagerEntity) {
        jdbcProvider.jdbcTemplate.update(
            """
            INSERT INTO card_group_manager (id, name, enabled)
            VALUES (?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name    = excluded.name,
                enabled = excluded.enabled
            """.trimIndent(),
            entity.id, entity.name, if (entity.enabled) 1 else 0
        )
    }

    fun findAllManagers(): List<CardManagerEntity> =
        jdbcProvider.jdbcTemplate.query("SELECT * FROM card_group_manager", managerRowMapper)

    fun deleteManager(id: String) {
        // 同时清理该 Manager 下的所有 Binding
        jdbcProvider.jdbcTemplate.update("DELETE FROM card_group_binding WHERE manger_id = ?", id)
        jdbcProvider.jdbcTemplate.update("DELETE FROM card_group_manager WHERE id = ?", id)
    }

    // ─────────────────────── Binding CRUD ──────────────────────────────────

    private val bindingRowMapper = RowMapper { rs, _ ->
        CardBindingEntity(
            id = rs.getString("id"),
            mangerId = rs.getString("manger_id"),
            sourceFile = rs.getString("source_file"),
            name = rs.getString("name"),
            cardIds = rs.getString("card_ids")
        )
    }

    fun saveBinding(entity: CardBindingEntity) {
        jdbcProvider.jdbcTemplate.update(
            """
            INSERT INTO card_group_binding (id, manger_id, source_file, name, card_ids)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                source_file = excluded.source_file,
                name        = excluded.name,
                card_ids    = excluded.card_ids
            """.trimIndent(),
            entity.id, entity.mangerId, entity.sourceFile, entity.name, entity.cardIds
        )
    }

    /** 替换某 Manager 下所有 Binding（先删后批量插） */
    fun replaceBindings(mangerId: String, entities: List<CardBindingEntity>) {
        jdbcProvider.jdbcTemplate.update("DELETE FROM card_group_binding WHERE manger_id = ?", mangerId)
        entities.forEach { saveBinding(it) }
    }

    fun findBindingsByManager(mangerId: String): List<CardBindingEntity> =
        jdbcProvider.jdbcTemplate.query(
            "SELECT * FROM card_group_binding WHERE manger_id = ?",
            bindingRowMapper, mangerId
        )

    fun deleteBinding(id: String) {
        jdbcProvider.jdbcTemplate.update(
            "DELETE FROM card_group_binding WHERE id = ?", id
        )
    }
}
