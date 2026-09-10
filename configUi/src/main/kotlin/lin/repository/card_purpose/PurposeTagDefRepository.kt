package lin.repository.card_purpose

import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate

/**
 * 标记定义（Tag Definition）。
 *
 * 术语见 `architecture-context/docs/术语表.md`：标记 Tag / 战略用途 Purpose / 用途绑定 PurposeBinding。
 * - [builtin] = true：系统内置**战略用途**（SAVE_LIFE / CLEAN / …），自带排序兜底与评估层绑定能力；
 * - [boundPurpose] = null：**纯标记**，零编排副作用，仅供条件树查询。
 */
data class PurposeTagDefEntity(
    val tagId: String,
    val displayName: String,
    val description: String? = null,
    val boundPurpose: String? = null,
    val builtin: Boolean = false,
    val createdDate: String? = null
)

/**
 * 标记定义仓储（T-TG-001）：标记的**唯一集中声明处**。
 *
 * 此前定义硬编码在 `DefaultPurposeTagProvider`，自定义标记无处登记；落库后白名单随之可增长。
 * 表结构改动不编码进 repository 运行时 ALTER（见 sqlite-schema-migration skill）：
 * 旧库迁移走 docs/sql/migrations 下的迁移脚本，此处只保证新库建表与内置种子。
 * （注意：KDoc 内勿写连续的斜杠星号，Kotlin 块注释可嵌套，会吞掉后续代码。）
 */
class PurposeTagDefRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS purpose_tag_def (
                tag_id        TEXT PRIMARY KEY,
                display_name  TEXT NOT NULL,
                description   TEXT,
                bound_purpose TEXT,
                builtin       INTEGER NOT NULL DEFAULT 0,
                created_date  TEXT
            );
            """.trimIndent()
        )
        seedBuiltin()
    }

    /**
     * 内置战略用途种子。
     *
     * `INSERT OR IGNORE`：已存在则不覆盖 —— 用户对内置用途的改名不会被启动重置。
     */
    private fun seedBuiltin() {
        BUILTIN_TAGS.forEach { (tagId, displayName) ->
            jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO purpose_tag_def
                    (tag_id, display_name, description, bound_purpose, builtin, created_date)
                VALUES (?, ?, ?, NULL, 1, ?)
                """.trimIndent(),
                tagId, displayName, BUILTIN_DESCRIPTION, LocalDate.now().toString()
            )
        }
    }

    private val rowMapper =
        org.springframework.jdbc.core.RowMapper { rs, _ ->
            PurposeTagDefEntity(
                tagId = rs.getString("tag_id"),
                displayName = rs.getString("display_name"),
                description = rs.getString("description"),
                boundPurpose = rs.getString("bound_purpose"),
                builtin = rs.getInt("builtin") != 0,
                createdDate = rs.getString("created_date")
            )
        }

    /** 内置优先、其余按 tagId 排序，保证 MCP list 与 UI 下拉顺序稳定。 */
    fun findAll(): List<PurposeTagDefEntity> = jdbcTemplate.query(
        """
        SELECT tag_id, display_name, description, bound_purpose, builtin, created_date
        FROM purpose_tag_def
        ORDER BY builtin DESC, tag_id ASC
        """.trimIndent(),
        rowMapper
    )

    fun findByTagId(tagId: String): PurposeTagDefEntity? = jdbcTemplate.query(
        """
        SELECT tag_id, display_name, description, bound_purpose, builtin, created_date
        FROM purpose_tag_def
        WHERE tag_id = ?
        """.trimIndent(),
        rowMapper,
        tagId
    ).firstOrNull()

    /**
     * 用途绑定映射：标记 → 战略用途（仅含已绑定的条目）。
     *
     * 供打标时展开（T-TG-001 实现形态 A：展开式）——引擎侧零改动。
     */
    fun bindingMap(): Map<String, String> = jdbcTemplate.query(
        """
        SELECT tag_id, bound_purpose
        FROM purpose_tag_def
        WHERE bound_purpose IS NOT NULL AND bound_purpose <> ''
        """.trimIndent()
    ) { rs, _ ->
        rs.getString("tag_id") to rs.getString("bound_purpose")
    }.toMap()

    /** 登记或更新标记定义（按 tagId 覆盖；[builtin] 不可通过本方法改写）。 */
    fun save(entity: PurposeTagDefEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO purpose_tag_def
                (tag_id, display_name, description, bound_purpose, builtin, created_date)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(tag_id) DO UPDATE SET
                display_name  = excluded.display_name,
                description   = excluded.description,
                bound_purpose = excluded.bound_purpose
            """.trimIndent(),
            entity.tagId,
            entity.displayName,
            entity.description,
            entity.boundPurpose,
            if (entity.builtin) 1 else 0,
            entity.createdDate ?: LocalDate.now().toString()
        )
    }

    fun deleteByTagId(tagId: String) {
        jdbcTemplate.update("DELETE FROM purpose_tag_def WHERE tag_id = ?", tagId)
    }

    companion object {
        const val BUILTIN_DESCRIPTION = "系统内置战略用途"

        /** 与 `DefaultPurposeTagProvider` 原有 7 个标签保持一致（顺序即 UI/MCP 展示顺序）。 */
        val BUILTIN_TAGS: List<Pair<String, String>> = listOf(
            "SAVE_LIFE" to "保命",
            "CLEAN" to "解场/清场",
            "GREED" to "成长/贪婪",
            "FINISH" to "斩杀",
            "VALUE" to "普通价值",
            "EXTRA_COST" to "额外费用",
            "DRAW_CARD" to "过牌"
        )
    }
}
