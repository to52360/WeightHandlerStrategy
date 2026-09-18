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

    /**
     * 惜售门槛 N（T-TG-029）。
     *
     * 为什么独立维度：N 的语义是「**够不够余费才垫**」（惜售），与 `PURPOSE_TIMING` 的
     * 「**何时出**」（阶段/次序/重规划）是两件事；此前挤在同一 payload 里，
     * 导致"只想调惜售"必须动时序声明，且 UI 面板把两件事混在一起。
     */
    const val PURPOSE_SURPLUS = "PURPOSE_SURPLUS"

    /**
     * 卡组侧的**用途排除**（「本卡组不使用该用途」= 消费侧的「去除」通道）。
     *
     * 为什么单独一个维度：D-TG-007 的「消费侧 = 只减 + 覆盖」里，"减"此前只落实在**树维度**
     * （`PURPOSE_TREE` 的额外排除若干棵树），时序 / 惜售**没有取消预设声明的通道**（只能逐字段覆盖值）。
     * 本维度补的正是这条：命中该维度的用途**整体退出本卡组** ——
     * ① **规则层**：从本卡组的规则集合里被减掉 ⇒ **无规则**（不参与 priority 选优、stage 落 GENERAL）；
     * ② **评估层**：该用途的用途树（全局共享 + 专属）**一并停用**（见 `DimensionItemResolver.purposeExcluded`）。
     *
     * ⚠️ **不会回落到全局默认值**：全局 `purpose_tag_rule` 只是**缺省值来源**、不是规则层，
     * 故"排除"的语义是**无规则**而非"用全局值"。
     * ⚠️ **只对 `scope = CARD_GROUP` 有意义**（预设是声明层，不声明即无，无需"去除"）。
     * ⚠️ 与 `PURPOSE_TREE` 的消费侧排除是**粗 / 细两层**：本维度 = 用途级（整个作用退出）；
     * 树级排除 = 在用途照常使用的前提下挑掉某几棵树。
     */
    const val PURPOSE_EXCLUDE = "PURPOSE_EXCLUDE"
 
    /**
     * 可被「用途排除」禁用的维度全集（D-TG-021）。
     *
     * `PURPOSE_EXCLUDE` payload 的 `{}` / 缺省解码为该全集（= 整用途退出）；
     * 新增可禁维度时须同步登记于此，以让「整用途退出」前向兼容新维度。
     */
    val EXCLUDABLE_DIMENSIONS = setOf(PURPOSE_TREE, PURPOSE_TIMING, PURPOSE_SURPLUS)

    /**
     * 光环作用域声明（D-DP-001 / Q-DP-002）—— **不是用途维度**：光环不按用途分组（`purpose_tag` 用哨兵
     * [AURA_ALL_TAGS] 整包承载），也**不受「用途排除」影响** ⇒ **不进** [EXCLUDABLE_DIMENSIONS]
     * （加进去会把光环卷进排除通道，且历史 `{}` payload 的解码含义会跟着变）。
     *
     * payload：预设侧 `{"auraIds":[…]}`（白名单）；消费侧 `{"extra":[…],"exclude":[…],"scoreOverrides":{…}}`。
     */
    const val AURA_BOOST = "AURA_BOOST"

    /**
     * `AURA_BOOST` 行的 `purpose_tag` **哨兵** —— 整包声明（光环不按用途分组）。
     *
     * 安全性（Q-DP-002 §8.1 已核查）：所有按用途读取的查询都带 `dimension` 过滤；唯一不带 dimension 的两处
     * 是快照的**原始行采集**（删预设 / 级联删卡组），对新维度天然通用。
     */
    const val AURA_ALL_TAGS = "_ALL_"
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

    // ─────────────────────── 惜售维度（T-TG-029，便捷读写） ───────────────────────

    /** 整体替换惜售声明；[SurplusOverride.isEmpty] 的行**不落库**（空操作无意义）。 */
    fun replaceSurplus(scope: String, ownerId: String, byTag: Map<String, SurplusOverride>) {
        val items = byTag.filterValues { !it.isEmpty }.map { (tag, override) ->
            DimensionItemEntity(
                scope = scope,
                ownerId = ownerId,
                dimension = Dimension.PURPOSE_SURPLUS,
                purposeTag = tag,
                payload = DimensionPayloadCodec.encodeSurplus(override)
            )
        }
        replaceItems(scope, ownerId, Dimension.PURPOSE_SURPLUS, items)
    }

    /** @return `用途 → 惜售声明` */
    fun findSurplus(scope: String, ownerId: String): Map<String, SurplusOverride> =
        findItems(scope, ownerId, Dimension.PURPOSE_SURPLUS)
            .associate { it.purposeTag to DimensionPayloadCodec.decodeSurplus(it.payload) }
    /**
     * 整体替换「本卡组不使用」的用途及其被禁维度（只对 `scope = CARD_GROUP` 有意义）。
     *
     * [byTag] = `用途 → 被禁维度集合`；**空集合 = 清除该用途的排除行**（= 不再排除）。
     * 维度集合 = [Dimension.EXCLUDABLE_DIMENSIONS] ⇒ payload 落 `{}`（整用途退出）；
     * 任一子集 ⇒ `{"dimensions":[…]}`（只禁列出的维度）。编解码统一走 [DimensionPayloadCodec.encodeExclusion]。
     */
    fun replaceExclusions(scope: String, ownerId: String, byTag: Map<String, Set<String>>) {
        val items = byTag.filterValues { it.isNotEmpty() }.map { (tag, dimensions) ->
            DimensionItemEntity(
                scope = scope,
                ownerId = ownerId,
                dimension = Dimension.PURPOSE_EXCLUDE,
                purposeTag = tag,
                payload = DimensionPayloadCodec.encodeExclusion(dimensions)
            )
        }
        replaceItems(scope, ownerId, Dimension.PURPOSE_EXCLUDE, items)
    }

    /** @return 被排除用途 → **被禁维度集合**（全集 = 整用途退出）。 */
    fun findExclusions(scope: String, ownerId: String): Map<String, Set<String>> =
        findItems(scope, ownerId, Dimension.PURPOSE_EXCLUDE).associate {
            it.purposeTag to DimensionPayloadCodec.decodeExclusion(it.payload)
        }

    // ─────────────────────── 光环维度（D-DP-001 / D-DP-002）───────────────────────

    /**
     * 整体替换预设侧**光环白名单**（`{"auraIds":[…]}`，整体替换语义）。
     *
     * 传空集合 ⇒ 落 `{"auraIds":[]}` = **声明为「不要任何全局光环」**（与"行不存在"在下游同效，但语义明确）。
     */
    fun replaceAuraSelection(scope: String, ownerId: String, auraIds: Collection<String>) {
        replaceItems(
            scope, ownerId, Dimension.AURA_BOOST,
            listOf(
                DimensionItemEntity(
                    scope = scope,
                    ownerId = ownerId,
                    dimension = Dimension.AURA_BOOST,
                    purposeTag = Dimension.AURA_ALL_TAGS,
                    payload = DimensionPayloadCodec.encodeAuraSelection(auraIds)
                )
            )
        )
    }

    /** @return 预设侧白名单（行不存在 = 未声明 ⇒ 空集）。 */
    fun findAuraSelection(scope: String, ownerId: String): Set<String> =
        findItems(scope, ownerId, Dimension.AURA_BOOST).firstOrNull()
            ?.let { DimensionPayloadCodec.decodeAuraSelection(it.payload) }
            ?: emptySet()

    /** 整体替换消费侧**光环增量**（[AuraDelta.isEmpty] ⇒ 清除该行）。 */
    fun replaceAuraDelta(scope: String, ownerId: String, delta: AuraDelta) {
        val items = if (delta.isEmpty) {
            emptyList()
        } else {
            listOf(
                DimensionItemEntity(
                    scope = scope,
                    ownerId = ownerId,
                    dimension = Dimension.AURA_BOOST,
                    purposeTag = Dimension.AURA_ALL_TAGS,
                    payload = DimensionPayloadCodec.encodeAuraDelta(delta)
                )
            )
        }
        replaceItems(scope, ownerId, Dimension.AURA_BOOST, items)
    }

    /** @return 消费侧光环增量（未声明 ⇒ [AuraDelta.NONE]）。 */
    fun findAuraDelta(scope: String, ownerId: String): AuraDelta =
        findItems(scope, ownerId, Dimension.AURA_BOOST).firstOrNull()
            ?.let { DimensionPayloadCodec.decodeAuraDelta(it.payload) }
            ?: AuraDelta.NONE

    /**
     * 按维度**全表扫描**（跨 scope / owner）—— D-DP-002 的删除悬空守卫用：
     * 删一条 `aura_boost` 行前，判断它是否被任何 `AURA_BOOST` 维度项引用（白名单 / extra / scoreOverrides）。
     */
    fun findAllByDimension(dimension: String): List<DimensionItemEntity> =
        jdbcTemplate.query(
            "SELECT * FROM strategy_dimension_item WHERE dimension = ? ORDER BY scope, owner_id, purpose_tag",
            itemRowMapper,
            dimension
        )

    /**
     * 该 tagId 的**活跃声明数**（任意 scope / 任意维度的维度项行数；D-DP-004 的降级与删除守卫用）。
     *
     * 「仍被声明引用」的含义：库里存在以该 tagId 为 `purpose_tag` 的维度项 —— 此时把标记降级
     * （`declarable = 0`）或删除定义，会让这些声明变成"写侧守门拒绝再编辑、引擎仍按旧值生效"的半悬空态
     * ⇒ 写侧拒绝该操作。
     */
    fun countByPurposeTag(tagId: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM strategy_dimension_item WHERE purpose_tag = ?",
            Int::class.java,
            tagId
        ) ?: 0
}
