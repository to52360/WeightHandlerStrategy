package lin.repository.card_group

import lin.bean.usePlan.PurposeTagId
import lin.bean.usePlan.PurposeTagIntentRule
import lin.bean.usePlan.UseStage
import org.junit.Assert.*
import org.junit.Test

/**
 * T-TG-015：维度项**纯合并规则**（树白名单 / 未声明禁用 / 消费方再减 / 时序逐字段 / payload presence）。
 *
 * 这些规则此前长在 `SqliteTreeConfigProvider` 里、因"构造合法评估树成本高"而无单测；
 * 抽成纯类后可直接断言（见 Q-TG-003 专项 9.4 / 11.5）。
 */
class DimensionItemResolverTest {

    private val resolver = DimensionItemResolver()

    private fun selection(
        presetKeep: Map<String, Set<String>>?,
        consumerExclude: Map<String, Set<String>> = emptyMap()
    ) = DimensionItemResolver.TreeSelection(presetKeep, consumerExclude)

    // ─────────────────────── 树：白名单 / 未声明禁用 ───────────────────────

    @Test
    fun `声明了树的用途只保留白名单内的树`() {
        val sel = selection(presetKeep = mapOf("CLEAN" to setOf("treeA", "treeB")))
        assertEquals(listOf("CLEAN"), resolver.narrowTreeTags("treeA", listOf("CLEAN"), sel))
        assertEquals(emptyList<String>(), resolver.narrowTreeTags("treeC", listOf("CLEAN"), sel))
    }

    @Test
    fun `未声明的用途其树全部禁用`() {
        val sel = selection(presetKeep = mapOf("CLEAN" to setOf("treeA")))
        assertEquals(emptyList<String>(), resolver.narrowTreeTags("treeX", listOf("GREED"), sel))
    }

    @Test
    fun `空预设（白名单为空表）禁用全部用途树`() {
        val sel = selection(presetKeep = emptyMap())
        assertEquals(emptyList<String>(), resolver.narrowTreeTags("treeA", listOf("CLEAN"), sel))
        assertEquals(emptyList<String>(), resolver.narrowTreeTags("treeB", listOf("GREED"), sel))
    }

    @Test
    fun `未引用预设时不做白名单裁剪`() {
        val sel = selection(presetKeep = null)
        assertEquals(listOf("CLEAN"), resolver.narrowTreeTags("treeA", listOf("CLEAN"), sel))
    }

    @Test
    fun `消费方可以在预设白名单之上再减`() {
        val sel = selection(
            presetKeep = mapOf("CLEAN" to setOf("treeA", "treeB")),
            consumerExclude = mapOf("CLEAN" to setOf("treeB"))
        )
        assertEquals(listOf("CLEAN"), resolver.narrowTreeTags("treeA", listOf("CLEAN"), sel))
        assertEquals(emptyList<String>(), resolver.narrowTreeTags("treeB", listOf("CLEAN"), sel))
    }

    @Test
    fun `多用途树按用途收窄而不是整棵误伤`() {
        // 树绑 [CLEAN, DRAW_CARD]；预设只给 CLEAN 选了它，DRAW_CARD 未声明 ⇒ 保留 CLEAN、剔掉 DRAW_CARD
        val sel = selection(presetKeep = mapOf("CLEAN" to setOf("multi")))
        assertEquals(
            listOf("CLEAN"),
            resolver.narrowTreeTags("multi", listOf("CLEAN", "DRAW_CARD"), sel)
        )
    }

    @Test
    fun `全部用途都被裁掉时返回空（调用方据此整棵不输出）`() {
        val sel = selection(presetKeep = mapOf("GREED" to setOf("other")))
        assertTrue(resolver.narrowTreeTags("multi", listOf("CLEAN", "DRAW_CARD"), sel).isEmpty())
    }

    @Test
    fun `treeSelection 在未引用预设时白名单为 null`() {
        val none = resolver.treeSelection(false, mapOf("CLEAN" to setOf("treeA")), emptyMap())
        assertNull(none.presetKeepByTag)

        val referenced = resolver.treeSelection(true, mapOf("CLEAN" to setOf("treeA")), emptyMap())
        assertEquals(mapOf("CLEAN" to setOf("treeA")), referenced.presetKeepByTag)
    }

    @Test
    fun `被禁用的用途清单等于已有用途树的用途减去已声明的用途`() {
        val universe = setOf("CLEAN", "GREED", "DRAW_CARD")
        assertEquals(
            listOf("DRAW_CARD", "GREED"),
            resolver.disabledPurposes(universe, presetDeclaredTags = setOf("CLEAN"))
        )
    }

    // ─────────────────────── 时序：逐字段合并 / K-TG-005 ───────────────────────

    private fun rule(
        stage: UseStage = UseStage.MID,
        weight: Double = 1.0,
        threshold: Int? = 1,
        replan: Boolean = false
    ) = PurposeTagIntentRule(
        tagId = PurposeTagId("CLEAN"),
        defaultStage = stage,
        defaultOrderWeight = weight,
        priority = 300,
        defaultReplanAfterUse = replan,
        defaultSurplusIdleThreshold = threshold
    )

    @Test
    fun `未声明的字段回落全局值`() {
        val merged = resolver.applyTiming(rule(), TimingOverride(defaultStage = "LATE"))
        assertEquals(UseStage.LATE, merged.defaultStage)
        assertEquals("未声明的 N 应回落全局", 1, merged.defaultSurplusIdleThreshold)
        assertEquals(1.0, merged.defaultOrderWeight, 0.0)
    }

    @Test
    fun `消费方逐字段压过预设`() {
        val merged = resolver.mergeTiming(
            lower = TimingOverride(defaultStage = "LATE", defaultOrderWeight = 5.0),
            upper = TimingOverride(defaultOrderWeight = 9.0)
        )
        assertEquals(UseStage.LATE.name, merged.defaultStage)
        assertEquals(9.0, merged.defaultOrderWeight!!, 0.0)
    }

    @Test
    fun `K-TG-005 显式声明 null 可把 N 覆盖为无门槛`() {
        val merged =
            resolver.applyTiming(rule(threshold = 1), TimingOverride(surplusIdleThreshold = ThresholdPatch(null)))
        assertNull("显式 null 应覆盖为「不设门槛」", merged.defaultSurplusIdleThreshold)
    }

    @Test
    fun `未出现该字段时不覆盖 N`() {
        val merged = resolver.applyTiming(rule(threshold = 1), TimingOverride(defaultStage = "LATE"))
        assertEquals(1, merged.defaultSurplusIdleThreshold)
    }

    @Test
    fun `空覆盖不改变规则`() {
        val base = rule()
        assertEquals(base, resolver.applyTiming(base, TimingOverride()))
    }

    // ─────────────────────── payload 编解码（单点） ───────────────────────

    @Test
    fun `树 id 列表编解码可往返`() {
        val payload = DimensionPayloadCodec.encodeTreeIds(listOf("t2", "t1", "t1"))
        assertEquals(setOf("t1", "t2"), DimensionPayloadCodec.decodeTreeIds(payload))
        assertTrue(DimensionPayloadCodec.encodeTreeIds(emptyList()).contains("[]"))
    }

    @Test
    fun `时序 payload 用键存在性区分未声明与显式 null`() {
        val cleared = DimensionPayloadCodec.decodeTiming(
            DimensionPayloadCodec.encodeTiming(TimingOverride(surplusIdleThreshold = ThresholdPatch(null)))
        )
        assertTrue("键存在 ⇒ 已声明", cleared.surplusIdleThreshold != null)
        assertNull("值为 null ⇒ 无门槛", cleared.surplusIdleThreshold!!.value)

        val absent = DimensionPayloadCodec.decodeTiming(
            DimensionPayloadCodec.encodeTiming(TimingOverride(defaultStage = "MID"))
        )
        assertNull("键不存在 ⇒ 未声明", absent.surplusIdleThreshold)
        assertFalse("stage 已声明却被当成空", absent.isEmpty)
    }
}
