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
    /**
     * **可声明作用**（D-DP-004 / Q-DP-001 晋级制）：true = 该**自定义**标记经晋级，可被预设 / 卡组增量项
     * 声明时序与惜售，从而拥有自己的编排行为（区别于「绑定」—— 那是继承内置作用的行为）。
     *
     * ⚠️ 只对 `builtin = false` 有意义：内置 7 个的可声明性由代码常量 `DECLARABLE_PURPOSES`（5 个）决定，
     * 本字段对内置行是**忽略域**（写侧忽略入参、UI 禁用、候选查询带 `builtin = 0`）。
     * ⚠️ 与 [boundPurpose] **互斥**：绑定的语义是「我的行为 = 那个作用」，晋级是「我自己就是作用」，
     * 同时成立 ⇒ 行为来源两个，写侧报错要求先解绑（一层绑定，D-TG-002）。
     */
    val declarable: Boolean = false,
    val createdDate: String? = null
)

/**
 * 标记定义仓储（T-TG-001）：标记的**唯一集中声明处**。
 *
 * 此前定义硬编码在 `DefaultPurposeTagProvider`，自定义标记无处登记；落库后白名单随之可增长。
 * 「可声明作用」候选集的**单一候选源**见 [declarableTagIds]（D-DP-004）。
 * 表结构改动不编码进 repository 运行时 ALTER（见 sqlite-schema-migration skill）：
 * 旧库升级由人工备份后用 sqlite3 执行 ALTER，此处只保证新库建表与内置种子。
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
                declarable    INTEGER NOT NULL DEFAULT 0,
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
     * `declarable` 显式写 0（D-DP-004：内置行的该列是忽略域，可声明性由代码常量决定）。
     */
    private fun seedBuiltin() {
        BUILTIN_TAGS.forEach { (tagId, displayName) ->
            jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO purpose_tag_def
                    (tag_id, display_name, description, bound_purpose, builtin, declarable, created_date)
                VALUES (?, ?, ?, NULL, 1, 0, ?)
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
                declarable = rs.getInt("declarable") != 0,
                createdDate = rs.getString("created_date")
            )
        }

    /** 内置优先、其余按 tagId 排序，保证 MCP list 与 UI 下拉顺序稳定。 */
    fun findAll(): List<PurposeTagDefEntity> = jdbcTemplate.query(
        """
        SELECT tag_id, display_name, description, bound_purpose, builtin, declarable, created_date
        FROM purpose_tag_def
        ORDER BY builtin DESC, tag_id ASC
        """.trimIndent(),
        rowMapper
    )

    fun findByTagId(tagId: String): PurposeTagDefEntity? = jdbcTemplate.query(
        """
        SELECT tag_id, display_name, description, bound_purpose, builtin, declarable, created_date
        FROM purpose_tag_def
        WHERE tag_id = ?
        """.trimIndent(),
        rowMapper,
        tagId
    ).firstOrNull()

    /**
     * **可声明作用全集**（D-DP-004 / Q-DP-001 晋级制）—— **单一候选源**，MCP 写侧守门与 UI 候选共用
     * （两处各拼一遍并集必然漂移）。
     *
     * 两层各归其位：
     * - **内置**：`PurposeTagRuleRepository.DECLARABLE_PURPOSES`（代码常量，5 个 = `BUILTIN_RULES` 的 tagId）。
     *   为什么不读本表：可声明性断言的是「引擎能否给该 tagId 产出 `PurposeTagIntentRule` 四字段行为」，
     *   由引擎侧（`DefaultPurposeTagIntentRuleProvider` + 三处机制耦合）决定 —— 属**能力**，改数据改不动它；
     *   且「7 个内置行里哪 5 个可声明」库里表达不出来（FINISH / EXTRA_COST 同为 `builtin = 1`）。
     * - **自定义晋级**：`builtin = 0 AND declarable = 1` 行（经 `save_purpose_tag_def(declarable = true)` 开启）。
     *
     * ⚠️ `builtin = 0` 过滤使表内内置行的 `declarable` 成为**忽略域**（写侧忽略入参、UI 禁用）。
     */
    fun declarableTagIds(): Set<String> =
        PurposeTagRuleRepository.DECLARABLE_PURPOSES + jdbcTemplate.query(
            "SELECT tag_id FROM purpose_tag_def WHERE declarable = 1 AND builtin = 0 ORDER BY tag_id"
        ) { rs, _ -> rs.getString("tag_id") }

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

    /**
     * 登记或更新标记定义（按 tagId 覆盖；[PurposeTagDefEntity.builtin] 不可通过本方法改写）。
     *
     * ⚠️ `declarable` **必须同时出现在 INSERT 列清单与 `ON CONFLICT DO UPDATE SET`**（D-DP-004）：
     * 只加列而不接管写入路径 ⇒ 任何一次「编辑标记后保存」都会把已开启的晋级静默重置为默认值。
     * 内置行（`builtin = true`）恒写 0 —— 该列对内置是忽略域（可声明性由代码常量决定）。
     */
    fun save(entity: PurposeTagDefEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO purpose_tag_def
                (tag_id, display_name, description, bound_purpose, builtin, declarable, created_date)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(tag_id) DO UPDATE SET
                display_name  = excluded.display_name,
                description   = excluded.description,
                bound_purpose = excluded.bound_purpose,
                declarable    = excluded.declarable
            """.trimIndent(),
            entity.tagId,
            entity.displayName,
            entity.description,
            entity.boundPurpose,
            if (entity.builtin) 1 else 0,
            if (entity.builtin || !entity.declarable) 0 else 1,
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
