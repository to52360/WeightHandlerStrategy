package lin.repository.card_group

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import lin.utils.runCatchingLog

/**
 * 「某字段**是否被声明**」与「声明的**值**」是两件事 —— 用包装类型表达：
 * `null` 包装 = **未声明**（回落下一层）；`ThresholdPatch(null)` = **声明为「不设门槛」**。
 *
 * 这是 K-TG-005 的解法：固定可空列做不到该区分，`payload` JSON 的**键存在性**可以。
 */
data class ThresholdPatch(val value: Int?)

/**
 * 用途时序**声明**（维度 `PURPOSE_TIMING` 的 payload 模型，D-TG-018）。
 *
 * **字段未出现 = 未声明**（回落缺省值源：全局 `purpose_tag_rule` 行 ⇒ 内置默认）。
 * 声明本身即「该用途有规则」——**未声明 = 无规则 = 不参与 `UseIntentDeriver` 的 priority 选优**。
 *
 * ⚠️ **N（惜售门槛）不属于本维度**（T-TG-029）：惜售与"何时出"是两件事（门槛管"够不够余费才垫"），
 * 已剥离到独立维度 [Dimension.PURPOSE_SURPLUS]，见 [SurplusOverride]。
 */
data class TimingOverride(
    val defaultStage: String? = null,
    val defaultOrderWeight: Double? = null,
    val defaultReplanAfterUse: Boolean? = null,
    /** priority 可选覆盖（D-TG-018）：未声明 ⇒ 取全局行 ⇒ 无全局行则内置默认 100。 */
    val priority: Int? = null
) {
    /** 所有字段都没声明 —— 该行是空操作（写库无意义）。 */
    val isEmpty: Boolean
        get() = defaultStage == null && defaultOrderWeight == null &&
                defaultReplanAfterUse == null && priority == null
}

/**
 * 用途**惜售声明**（维度 `PURPOSE_SURPLUS` 的 payload 模型，T-TG-029）。
 *
 * 语义：某用途的牌「平时惜售、余费充足才垫」的门槛 N。
 * 三态由 [ThresholdPatch] 承载 —— `null`（整行不存在/字段缺失）= **未声明**（回落全局行）
 * vs `ThresholdPatch(null)` = **声明为「不设门槛」**。
 */
data class SurplusOverride(
    val surplusIdleThreshold: ThresholdPatch? = null
) {
    /** 未声明任何字段 —— 该行是空操作（写库无意义）。 */
    val isEmpty: Boolean
        get() = surplusIdleThreshold == null
}

/**
 * 消费侧**光环增量**（维度 `AURA_BOOST` 的 payload 模型，D-DP-002）。
 *
 * 三档：`extra`（白名单外补声明）/ `exclude`（再减）/ `scoreOverrides`（覆盖分值）；
 * 生效集 = 白名单 ∪ [extra] − [exclude]（见 `DimensionItemResolver.auraEffective`）。
 *
 * ⚠️ `extra ∩ exclude` 非空是**非法输入**（写侧报错，对齐 D-TG-021 ③ 的「同 id 双写」拍板）。
 */
data class AuraDelta(
    val extra: Set<String> = emptySet(),
    val exclude: Set<String> = emptySet(),
    val scoreOverrides: Map<String, Double> = emptyMap()
) {
    /** 三档皆空 —— 该行是空操作（写库无意义）。 */
    val isEmpty: Boolean
        get() = extra.isEmpty() && exclude.isEmpty() && scoreOverrides.isEmpty()

    companion object {
        /** 未声明（未引用预设 / 无增量项）。 */
        val NONE = AuraDelta()
    }
}

/**
 * 维度值的 `payload` JSON **编解码单点**（同 D-007 存储编码单点的精神）—— 其它地方禁止各写一套。
 *
 * 规则：**键留列**（`scope` / `owner_id` / `dimension` / `purpose_tag`），**值进 payload**。
 * - `PURPOSE_TREE` → `{"treeIds":[…]}`（空数组 = 声明为「一棵都不要」）
 * - `PURPOSE_TIMING` → `{"defaultStage":…}`（**只写已声明字段** ⇒「字段未出现」= 不覆盖）
 * - `PURPOSE_SURPLUS`（T-TG-029）→ `{"defaultSurplusIdleThreshold":…}`（**键一直写**，
 *   JSON `null` = 声明为「不设门槛」vs 键缺失 = 未声明）
 */
object DimensionPayloadCodec {

    /** 惜售门槛在 payload 里的字段名（编解码两侧共用，防手抄漂移）。 */
    private const val FIELD_SURPLUS_THRESHOLD = "defaultSurplusIdleThreshold"

    private val mapper = jacksonObjectMapper()

    // ─────────────────────── PURPOSE_TREE ───────────────────────

    fun encodeTreeIds(treeIds: Collection<String>): String {
        val node = mapper.createObjectNode()
        val array = node.putArray("treeIds")
        treeIds.distinct().sorted().forEach { array.add(it) }
        return mapper.writeValueAsString(node)
    }

    fun decodeTreeIds(payload: String?): Set<String> {
        val array = parse(payload)?.get("treeIds") ?: return emptySet()
        if (!array.isArray) return emptySet()
        return array.mapNotNull { it.takeUnless { node -> node.isNull }?.asText() }.toSet()
    }

    // ─────────────────────── PURPOSE_TIMING ───────────────────────

    fun encodeTiming(override: TimingOverride): String {
        val node = mapper.createObjectNode()
        override.defaultStage?.let { node.put("defaultStage", it) }
        override.defaultOrderWeight?.let { node.put("defaultOrderWeight", it) }
        override.defaultReplanAfterUse?.let { node.put("defaultReplanAfterUse", it) }
        override.priority?.let { node.put("priority", it) }
        return mapper.writeValueAsString(node)
    }

    fun decodeTiming(payload: String?): TimingOverride {
        val node = parse(payload) ?: return TimingOverride()
        return TimingOverride(
            defaultStage = node.textOrNull("defaultStage"),
            defaultOrderWeight = node.doubleOrNull("defaultOrderWeight"),
            defaultReplanAfterUse = node.booleanOrNull("defaultReplanAfterUse"),
            priority = node.intOrNull("priority")
        )
    }

    // ─────────────────────── PURPOSE_SURPLUS（T-TG-029）───────────────────────

    /** 编码惜售声明：**键一直写**（`null` 落成 JSON null ⇒ 与"未声明"可分）。 */
    fun encodeSurplus(override: SurplusOverride): String {
        val node = mapper.createObjectNode()
        val patch = override.surplusIdleThreshold
        if (patch != null) {
            val value = patch.value
            if (value == null) node.putNull(FIELD_SURPLUS_THRESHOLD)
            else node.put(FIELD_SURPLUS_THRESHOLD, value)
        }
        return mapper.writeValueAsString(node)
    }

    fun decodeSurplus(payload: String?): SurplusOverride {
        val node = parse(payload) ?: return SurplusOverride()
        // ⚠️ 「是否声明」由键存在性承载 —— 不能被 takeUnless { isNull } 一并抹掉
        return SurplusOverride(
            surplusIdleThreshold = if (node.has(FIELD_SURPLUS_THRESHOLD)) {
                ThresholdPatch(node.get(FIELD_SURPLUS_THRESHOLD).takeUnless { it.isNull }?.asInt())
            } else {
                null
            }
        )
    }

 
    // ─────────────────────── AURA_BOOST（D-DP-001 / D-DP-002：光环作用域）───────────────────────

    private const val FIELD_AURA_IDS = "auraIds"
    private const val FIELD_AURA_EXTRA = "extra"
    private const val FIELD_AURA_EXCLUDE = "exclude"
    private const val FIELD_AURA_SCORE_OVERRIDES = "scoreOverrides"

    /** 预设侧**白名单**：`{"auraIds":[…]}`。 */
    fun encodeAuraSelection(auraIds: Collection<String>): String {
        val node = mapper.createObjectNode()
        val array = node.putArray(FIELD_AURA_IDS)
        auraIds.distinct().sorted().forEach { array.add(it) }
        return mapper.writeValueAsString(node)
    }

    fun decodeAuraSelection(payload: String?): Set<String> =
        parse(payload)?.get(FIELD_AURA_IDS).stringSet()

    /** 消费侧**三档增量**：`{"extra":[…],"exclude":[…],"scoreOverrides":{"<id>":score}}`。 */
    fun encodeAuraDelta(delta: AuraDelta): String {
        val node = mapper.createObjectNode()
        val extra = node.putArray(FIELD_AURA_EXTRA)
        delta.extra.distinct().sorted().forEach { extra.add(it) }
        val exclude = node.putArray(FIELD_AURA_EXCLUDE)
        delta.exclude.distinct().sorted().forEach { exclude.add(it) }
        val overrides = node.putObject(FIELD_AURA_SCORE_OVERRIDES)
        delta.scoreOverrides.toSortedMap().forEach { (id, score) -> overrides.put(id, score) }
        return mapper.writeValueAsString(node)
    }

    fun decodeAuraDelta(payload: String?): AuraDelta {
        val node = parse(payload) ?: return AuraDelta.NONE
        val overrides = node.get(FIELD_AURA_SCORE_OVERRIDES)
        return AuraDelta(
            extra = node.get(FIELD_AURA_EXTRA).stringSet(),
            exclude = node.get(FIELD_AURA_EXCLUDE).stringSet(),
            scoreOverrides = if (overrides == null || !overrides.isObject) {
                emptyMap()
            } else {
                overrides.fields().asSequence()
                    .filter { it.value.isNumber }
                    .associate { it.key to it.value.asDouble() }
            }
        )
    }

    /** 数组节点 → 字符串集合；null / 非数组 ⇒ 空集（与 `decodeTreeIds` 同口径）。 */
    private fun JsonNode?.stringSet(): Set<String> {
        if (this == null || !isArray) return emptySet()
        return mapNotNull { it.takeUnless { node -> node.isNull }?.asText() }.toSet()
    }

    // ─────────────────────── PURPOSE_EXCLUDE（D-TG-021：维度级排除） ───────────────────────
 
    /** 排除 payload 里携带被禁维度的字段名（编解码两侧共用）。 */
    private const val FIELD_EXCLUDED_DIMENSIONS = "dimensions"
 
    /**
     * 编码「被禁维度集合」。
     *
     * `null` 或**等于已知全集** ⇒ 落 `{}`（**整用途退出**，与存量同形，零迁移）；
     * 否则 ⇒ `{"dimensions":["PURPOSE_TREE",…]}`（**只禁列出的维度**）。
     */
    fun encodeExclusion(dimensions: Set<String>?): String {
        if (dimensions == null || dimensions == Dimension.EXCLUDABLE_DIMENSIONS) return "{}"
        val node = mapper.createObjectNode()
        val array = node.putArray(FIELD_EXCLUDED_DIMENSIONS)
        dimensions.distinct().sorted().forEach { array.add(it) }
        return mapper.writeValueAsString(node)
    }
 
    /**
     * 解码为「被禁维度集合」。
     *
     * `{}` / 缺键 / 非数组 / **空数组** ⇒ 全集（= 整用途退出，守住「行存在 = 被排除」不变式）；
     * 否则 ⇒ 列出的子集。
     */
    fun decodeExclusion(payload: String?): Set<String> {
        val array = parse(payload)?.get(FIELD_EXCLUDED_DIMENSIONS) ?: return Dimension.EXCLUDABLE_DIMENSIONS
        if (!array.isArray) return Dimension.EXCLUDABLE_DIMENSIONS
        val dims = array.mapNotNull { it.takeUnless { n -> n.isNull }?.asText() }.toSet()
        return dims.ifEmpty { Dimension.EXCLUDABLE_DIMENSIONS }
    }

    // ─────────────────────── 内部 ───────────────────────

    private fun parse(payload: String?): JsonNode? {
        if (payload.isNullOrBlank()) return null
        val node = runCatchingLog("维度项 payload 解析失败，按「未声明」处理: $payload") {
            mapper.readTree(payload)
        }.getOrNull() ?: return null
        return node.takeIf { it.isObject }
    }

    private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeUnless { it.isNull }?.asText()

    private fun JsonNode.doubleOrNull(field: String): Double? = get(field)?.takeUnless { it.isNull }?.asDouble()

    private fun JsonNode.booleanOrNull(field: String): Boolean? = get(field)?.takeUnless { it.isNull }?.asBoolean()

    /** ⚠️ 非数值节点（如脏字符串）⇒ 视为未声明，不得让 `asInt()` 把 `"abc"` 静默解成 0。 */
    private fun JsonNode.intOrNull(field: String): Int? =
        get(field)?.takeUnless { it.isNull }?.takeIf { it.isNumber }?.asInt()
}
