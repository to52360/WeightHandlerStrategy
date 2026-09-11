package lin.repository.card_group

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

/** 用途预设锚表 `strategy_preset` 的一行（元信息；内容在 `strategy_dimension_item`）。 */
data class StrategyPresetEntity(
    val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: String? = null
)

/** 维度项归属域。 */
object DimensionScope {
    /** 预设自己声明的项（正向）。 */
    const val PRESET = "PRESET"

    /** 卡组引用预设时的增量项（负向：再减 / 覆盖）。 */
    const val CARD_GROUP = "CARD_GROUP"
}

/** 维度枚举值。 */
object Dimension {
    const val PURPOSE_TREE = "PURPOSE_TREE"
    const val PURPOSE_TIMING = "PURPOSE_TIMING"
}

/**
 * 维度项（`strategy_dimension_item` 一行）。
 *
 * **统一语义**：**行 = (归属域, 归属方, 维度, 用途) 的一条声明；[payload] = 该声明的值**。
 * 键留列、值进 payload（如 `PURPOSE_TREE` → `{"treeIds":[…]}`），编解码走 [DimensionPayloadCodec]。
 */
data class DimensionItemEntity(
    val scope: String,
    val ownerId: String,
    val dimension: String,
    val purposeTag: String,
    val payload: String
)

/**
 * 用途预设仓储（T-TG-015）—— 方案见 `cross-dialogue/Q-TG-003-use-preset-final.md`。
 *
 * 管两张表：
 * - `strategy_preset`：预设**锚**（元信息）；
 * - `strategy_dimension_item`：**维度项单表** —— 预设项与卡组增量项**结构同构**，
 *   仅靠 `scope` 区分（`PRESET` / `CARD_GROUP`），故不再各建一对表。
 *
 * 表结构改动不编码进 repository 运行时 ALTER（见 sqlite-schema-migration skill）：
 * 旧库迁移走 docs/sql/migrations 下的迁移脚本，此处只保证新库建表。
 */
class StrategyPresetRepository(private val jdbcTemplate: JdbcTemplate) {

    init {
        initSchema()
    }

    private fun initSchema() {
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_preset (
                id          TEXT PRIMARY KEY,
                name        TEXT NOT NULL,
                description TEXT,
                created_at  TEXT
            );
            """.trimIndent()
        )
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_dimension_item (
                scope       TEXT NOT NULL,
                owner_id    TEXT NOT NULL,
                dimension   TEXT NOT NULL,
                purpose_tag TEXT NOT NULL,
                payload     TEXT NOT NULL,
                PRIMARY KEY (scope, owner_id, dimension, purpose_tag)
            );
            """.trimIndent()
        )
    }

    // ─────────────────────── 预设锚 ───────────────────────

    private val presetRowMapper = RowMapper { rs, _ ->
        StrategyPresetEntity(
            id = rs.getString("id"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            createdAt = rs.getString("created_at")
        )
    }

    fun savePreset(entity: StrategyPresetEntity) {
        jdbcTemplate.update(
            """
            INSERT INTO strategy_preset (id, name, description, created_at)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                name        = excluded.name,
                description = excluded.description
            """.trimIndent(),
            entity.id, entity.name, entity.description, entity.createdAt
        )
    }

    fun findAllPresets(): List<StrategyPresetEntity> =
        jdbcTemplate.query("SELECT * FROM strategy_preset ORDER BY created_at DESC, id ASC", presetRowMapper)

    fun findPresetById(id: String): StrategyPresetEntity? =
        jdbcTemplate.query("SELECT * FROM strategy_preset WHERE id = ?", presetRowMapper, id).firstOrNull()

    /** 级联删：维度项 + 锚。 */
    fun deletePreset(id: String) {
        deleteItems(DimensionScope.PRESET, id)
        jdbcTemplate.update("DELETE FROM strategy_preset WHERE id = ?", id)
    }

    // ─────────────────────── 维度项 ───────────────────────

    private val itemRowMapper = RowMapper { rs, _ ->
        DimensionItemEntity(
            scope = rs.getString("scope"),
            ownerId = rs.getString("owner_id"),
            dimension = rs.getString("dimension"),
            purposeTag = rs.getString("purpose_tag"),
            payload = rs.getString("payload")
        )
    }

    /**
     * 整体替换某维度项集合（**覆盖语义**：先清同维度行再插；调用方在事务内使用）。
     *
     * 维度级替换 —— 换树不会碰时序，反之亦然。
     */
    fun replaceItems(scope: String, ownerId: String, dimension: String, items: List<DimensionItemEntity>) {
        jdbcTemplate.update(
            "DELETE FROM strategy_dimension_item WHERE scope = ? AND owner_id = ? AND dimension = ?",
            scope, ownerId, dimension
        )
        items.distinctBy { it.purposeTag }.forEach { item ->
            jdbcTemplate.update(
                """
                INSERT OR IGNORE INTO strategy_dimension_item
                    (scope, owner_id, dimension, purpose_tag, payload)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                scope, ownerId, dimension, item.purposeTag, item.payload
            )
        }
    }

    /** @param dimension null = 该 owner 的全部维度项 */
    fun findItems(scope: String, ownerId: String, dimension: String? = null): List<DimensionItemEntity> =
        if (dimension == null) {
            jdbcTemplate.query(
                "SELECT * FROM strategy_dimension_item WHERE scope = ? AND owner_id = ? ORDER BY dimension, purpose_tag",
                itemRowMapper, scope, ownerId
            )
        } else {
            jdbcTemplate.query(
                "SELECT * FROM strategy_dimension_item WHERE scope = ? AND owner_id = ? AND dimension = ? ORDER BY purpose_tag",
                itemRowMapper, scope, ownerId, dimension
            )
        }

    fun deleteItems(scope: String, ownerId: String) {
        jdbcTemplate.update(
            "DELETE FROM strategy_dimension_item WHERE scope = ? AND owner_id = ?",
            scope, ownerId
        )
    }

    // ─────────────────────── 树维度（便捷读写） ───────────────────────

    /**
     * 整体替换树选择：`用途 → 保留的树 id 集合`。
     *
     * ⚠️ **传了空集合的用途也会落一行**（`{"treeIds":[]}`）—— 表达"该用途已声明、结果为不要任何树"，
     * 与"未声明"在下游同效，但**能把它从"被禁用的用途清单"里排除**（声明过的不算被静默禁用）。
     */
    fun replaceTreeSelections(scope: String, ownerId: String, byTag: Map<String, Collection<String>>) {
        val items = byTag.map { (tag, treeIds) ->
            DimensionItemEntity(
                scope = scope,
                ownerId = ownerId,
                dimension = Dimension.PURPOSE_TREE,
                purposeTag = tag,
                payload = DimensionPayloadCodec.encodeTreeIds(treeIds)
            )
        }
        replaceItems(scope, ownerId, Dimension.PURPOSE_TREE, items)
    }

    /** @return `用途 → 保留/排除的树 id 集合` */
    fun findTreeSelections(scope: String, ownerId: String): Map<String, Set<String>> =
        findItems(scope, ownerId, Dimension.PURPOSE_TREE)
            .associate { it.purposeTag to DimensionPayloadCodec.decodeTreeIds(it.payload) }

    // ─────────────────────── 时序维度（便捷读写） ───────────────────────

    /** 整体替换时序覆盖；[TimingOverride.isEmpty] 的行**不落库**（空操作无意义）。 */
    fun replaceTimings(scope: String, ownerId: String, byTag: Map<String, TimingOverride>) {
        val items = byTag.filterValues { !it.isEmpty }.map { (tag, override) ->
            DimensionItemEntity(
                scope = scope,
                ownerId = ownerId,
                dimension = Dimension.PURPOSE_TIMING,
                purposeTag = tag,
                payload = DimensionPayloadCodec.encodeTiming(override)
            )
        }
        replaceItems(scope, ownerId, Dimension.PURPOSE_TIMING, items)
    }

    /** @return `用途 → 时序覆盖` */
    fun findTimings(scope: String, ownerId: String): Map<String, TimingOverride> =
        findItems(scope, ownerId, Dimension.PURPOSE_TIMING)
            .associate { it.purposeTag to DimensionPayloadCodec.decodeTiming(it.payload) }
}
