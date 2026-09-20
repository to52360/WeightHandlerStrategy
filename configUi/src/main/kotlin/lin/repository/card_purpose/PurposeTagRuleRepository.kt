package lin.repository.card_purpose

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/**
 * 用途意图规则（第一层排序兜底）。
 *
 * 术语见 `architecture-context/docs/术语表.md`；形态决策见 D-TG-003。
 * - **行存在 = 有规则**（参与 `UseIntentDeriver` 的 priority 选优）；
 * - **无行 = 无规则**（如 FINISH / 纯标记）；
 * - 字段级二态：列值即最终值，不区分「未声明」与「声明为默认值」。
 *
 * ⚠️ 不可用「行存在但列全默认值」冒充无规则 —— 那会以默认 priority=100 参与选优。
 */
data class PurposeTagRuleEntity(
    val tagId: String,
    val defaultStage: String,
    val defaultOrderWeight: Double = 0.0,
    val defaultSurplusIdleThreshold: Int? = null,
    val defaultReplanAfterUse: Boolean = false,
    val priority: Int = 100
)

/**
 * 用途意图规则仓储（T-TG-007）。
 *
 * 与 [PurposeTagDefRepository] 的分工：定义表管「有哪些标记」，本表管「某些标记带来什么排序默认值」。
 * 表结构改动不编码进 repository 运行时 ALTER（见 sqlite-schema-migration skill）：
 * 旧库升级由人工备份后用 sqlite3 执行 ALTER，此处只保证新库建表与内置种子。
 */
class PurposeTagRuleRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS purpose_tag_rule (
                tag_id                         TEXT PRIMARY KEY,
                default_stage                  TEXT    NOT NULL,
                default_order_weight           REAL    NOT NULL DEFAULT 0.0,
                default_surplus_idle_threshold INTEGER,
                default_replan_after_use       INTEGER NOT NULL DEFAULT 0,
                priority                       INTEGER NOT NULL DEFAULT 100
            );
            """.trimIndent()
        )
        seedBuiltin()
    }

    /** `INSERT OR IGNORE`：已存在则不覆盖 —— 用户对内置规则的调参不会被启动重置。 */
    private fun seedBuiltin() {
        BUILTIN_RULES.forEach { rule ->
            jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO purpose_tag_rule
                    (tag_id, default_stage, default_order_weight,
                     default_surplus_idle_threshold, default_replan_after_use, priority)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                rule.tagId,
                rule.defaultStage,
                rule.defaultOrderWeight,
                rule.defaultSurplusIdleThreshold,
                if (rule.defaultReplanAfterUse) 1 else 0,
                rule.priority
            )
        }
    }

    private val rowMapper = RowMapper { rs, _ ->
        PurposeTagRuleEntity(
            tagId = rs.getString("tag_id"),
            defaultStage = rs.getString("default_stage"),
            defaultOrderWeight = rs.getDouble("default_order_weight"),
            defaultSurplusIdleThreshold = rs.getInt("default_surplus_idle_threshold")
                .takeUnless { rs.wasNull() },
            defaultReplanAfterUse = rs.getInt("default_replan_after_use") != 0,
            priority = rs.getInt("priority")
        )
    }

    fun findAll(): List<PurposeTagRuleEntity> = jdbcTemplate.query(
        """
        SELECT tag_id, default_stage, default_order_weight,
               default_surplus_idle_threshold, default_replan_after_use, priority
        FROM purpose_tag_rule
        ORDER BY priority DESC, tag_id ASC
        """.trimIndent(),
        rowMapper
    )

    fun findByTagId(tagId: String): PurposeTagRuleEntity? = jdbcTemplate.query(
        """
        SELECT tag_id, default_stage, default_order_weight,
               default_surplus_idle_threshold, default_replan_after_use, priority
        FROM purpose_tag_rule
        WHERE tag_id = ?
        """.trimIndent(),
        rowMapper,
        tagId
    ).firstOrNull()

    /** 登记或更新规则（按 tagId 覆盖；「无规则」= 不调用本方法 / 调用 [deleteByTagId]）。 */
    fun save(entity: PurposeTagRuleEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO purpose_tag_rule
                (tag_id, default_stage, default_order_weight,
                 default_surplus_idle_threshold, default_replan_after_use, priority)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(tag_id) DO UPDATE SET
                default_stage                  = excluded.default_stage,
                default_order_weight           = excluded.default_order_weight,
                default_surplus_idle_threshold = excluded.default_surplus_idle_threshold,
                default_replan_after_use       = excluded.default_replan_after_use,
                priority                       = excluded.priority
            """.trimIndent(),
            entity.tagId,
            entity.defaultStage,
            entity.defaultOrderWeight,
            entity.defaultSurplusIdleThreshold,
            if (entity.defaultReplanAfterUse) 1 else 0,
            entity.priority
        )
    }

    /** 删除规则 = 该标记退出选优（退化为「无规则」，如 FINISH）。 */
    fun deleteByTagId(tagId: String) {
        jdbcTemplate.update("DELETE FROM purpose_tag_rule WHERE tag_id = ?", tagId)
    }

    companion object {
        /**
         * 与 `DefaultPurposeTagIntentRuleProvider` 现值逐字对齐。
         *
         * **无条目**：`FINISH`（斩杀是局面属性，Q-033）／`EXTRA_COST`（T-TG-031：由 `ExtCostStrategy`
         * 接管时机、不进 `UsePlanOrderer` ⇒ 规则行是无效旋钮，且会以 priority=50 与 VALUE 平手）。
         */
        val BUILTIN_RULES: List<PurposeTagRuleEntity> = listOf(
            PurposeTagRuleEntity(
                tagId = "SAVE_LIFE", defaultStage = "LATE",
                priority = 400, defaultSurplusIdleThreshold = 1
            ),
            PurposeTagRuleEntity(
                tagId = "CLEAN", defaultStage = "MID",
                defaultOrderWeight = 1.0, priority = 300, defaultSurplusIdleThreshold = 1
            ),
            PurposeTagRuleEntity(tagId = "GREED", defaultStage = "SETUP", priority = 100),
            PurposeTagRuleEntity(tagId = "VALUE", defaultStage = "GENERAL", priority = 50),
            PurposeTagRuleEntity(tagId = "DRAW_CARD", defaultStage = "MID", priority = 60)
        )

        /**
         * **可被声明的「作用」清单**（T-TG-038 / D-TG-019）= 有行为定义的内置作用。
         *
         * 为什么必须与「有全局规则行」区分：用途预设 / 卡组增量项**只允许**对清单内的 tagId 声明时序与惜售。
         * 若改拿 `purpose_tag_rule` **表行**当候选，删一行 / 漏一次迁移就会让 UI 候选**静默缩水**，
         * 并在保存（维度级整体替换）时抹掉"UI 看不见的声明"。清单是**能力**（代码常量，恒在），表行是**业务值**
         * （默认值预填，可缺 ⇒ 缺失时回落 [BUILTIN_RULES]）。
         * ⚠️ 新增 / 删除作用 = 同时改本清单与引擎 `DefaultPurposeTagIntentRuleProvider`（守门用例保证逐字一致）。
         */
        val DECLARABLE_PURPOSES: Set<String> = BUILTIN_RULES.map { it.tagId }.toSet()
    }
}
