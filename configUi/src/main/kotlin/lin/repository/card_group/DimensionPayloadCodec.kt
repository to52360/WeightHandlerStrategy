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
 * **字段未出现 = 未声明**（回落缺省值源：全局 `purpose_tag_rule` 行 ⇒ 内置默认）；
 * `surplusIdleThreshold` 例外（三态，见 [ThresholdPatch]）。
 * 声明本身即「该用途有规则」——**未声明 = 无规则 = 不参与 `UseIntentDeriver` 的 priority 选优**。
 */
data class TimingOverride(
    val defaultStage: String? = null,
    val defaultOrderWeight: Double? = null,
    val defaultReplanAfterUse: Boolean? = null,
    val surplusIdleThreshold: ThresholdPatch? = null,
    /** priority 可选覆盖（D-TG-018）：未声明 ⇒ 取全局行 ⇒ 无全局行则内置默认 100。 */
    val priority: Int? = null
) {
    /** 所有字段都没声明 —— 该行是空操作（写库无意义）。 */
    val isEmpty: Boolean
        get() = defaultStage == null && defaultOrderWeight == null &&
                defaultReplanAfterUse == null && surplusIdleThreshold == null && priority == null
}

/**
 * 维度值的 `payload` JSON **编解码单点**（同 D-007 存储编码单点的精神）—— 其它地方禁止各写一套。
 *
 * 规则：**键留列**（`scope` / `owner_id` / `dimension` / `purpose_tag`），**值进 payload**。
 * - `PURPOSE_TREE` → `{"treeIds":[…]}`（空数组 = 声明为「一棵都不要」）
 * - `PURPOSE_TIMING` → `{"defaultStage":…}`（**只写已声明字段** ⇒
 *   「字段未出现」与「字段出现且为 null」天然可分：前者 = 不覆盖、后者 = 覆盖为「无门槛」）
 */
object DimensionPayloadCodec {

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
        override.surplusIdleThreshold?.let { patch ->
            val value = patch.value
            if (value == null) node.putNull("defaultSurplusIdleThreshold")
            else node.put("defaultSurplusIdleThreshold", value)
        }
        return mapper.writeValueAsString(node)
    }

    fun decodeTiming(payload: String?): TimingOverride {
        val node = parse(payload) ?: return TimingOverride()
        return TimingOverride(
            defaultStage = node.textOrNull("defaultStage"),
            defaultOrderWeight = node.doubleOrNull("defaultOrderWeight"),
            defaultReplanAfterUse = node.booleanOrNull("defaultReplanAfterUse"),
            priority = node.intOrNull("priority"),
            // ⚠️ N 的「是否声明」由键存在性承载 —— 不能被 takeUnless { isNull } 一并抹掉
            surplusIdleThreshold = if (node.has("defaultSurplusIdleThreshold")) {
                ThresholdPatch(node.get("defaultSurplusIdleThreshold").takeUnless { it.isNull }?.asInt())
            } else {
                null
            }
        )
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
