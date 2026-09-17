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
        presetKeep: Map<String, Set<String>>,
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
    fun `未引用预设时用途树不输出`() {
        // D-TG-018：不引用预设 = 一条声明都没有 ⇒ 全局共享用途树全部不输出（与空预设同效）
        val sel = resolver.treeSelection(false, mapOf("CLEAN" to setOf("treeA")), emptyMap())
        assertEquals(emptyList<String>(), resolver.narrowTreeTags("treeA", listOf("CLEAN"), sel))
        assertEquals(emptyList<String>(), resolver.narrowTreeTags("treeB", listOf("GREED"), sel))
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
    fun `用途级排除（本卡组不使用）连带停用其用途树`() {
        // D-TG-020：排除是**用途级**的粗粒度 —— 规则被减掉，用途树（全局共享 + 专属）也一并停用
        val sel = resolver.treeSelection(
            presetReferenced = true,
            presetKeepByTag = mapOf("CLEAN" to setOf("treeA")),
            consumerExcludeByTag = emptyMap(),
            excludedPurposes = setOf("CLEAN")
        )
        assertEquals(
            "全局共享用途树：被排除的用途不输出",
            emptyList<String>(),
            resolver.narrowTreeTags("treeA", listOf("CLEAN"), sel)
        )
        assertEquals(
            "卡组专属树：同样受用途级排除约束",
            emptyList<String>(),
            resolver.narrowOwnTreeTags("own", listOf("CLEAN"), sel)
        )
    }

    // ─────────────────── 树：卡组专属（Q-TG-004 形态 D：归属即拥有） ───────────────────

    @Test
    fun `卡组专属树不受预设白名单约束`() {
        val declaredOnlyA = selection(presetKeep = mapOf("CLEAN" to setOf("treeA")))
        // 专属树既没进白名单、其用途也可能没被声明 —— 归属即拥有 ⇒ 照旧保留
        assertEquals(listOf("CLEAN"), resolver.narrowOwnTreeTags("own", listOf("CLEAN"), declaredOnlyA))
        assertEquals(listOf("GREED"), resolver.narrowOwnTreeTags("own", listOf("GREED"), declaredOnlyA))
        // 空预设（"全禁"语义）同样不作用于专属树：预设管的是公共（无归属）资源
        assertEquals(
            listOf("CLEAN"),
            resolver.narrowOwnTreeTags("own", listOf("CLEAN"), selection(presetKeep = emptyMap()))
        )
        // 对照：同一选择下，全局共享树被裁 ⇒ 证明"豁免"是专属树独有
        assertEquals(
            emptyList<String>(),
            resolver.narrowTreeTags("own", listOf("CLEAN"), declaredOnlyA)
        )
    }

    @Test
    fun `卡组专属树仍受消费方增量项约束`() {
        val sel = selection(presetKeep = emptyMap(), consumerExclude = mapOf("CLEAN" to setOf("own")))
        assertEquals("本卡组可把自己的专属树再排除（微调入口保留）", emptyList<String>(), resolver.narrowOwnTreeTags("own", listOf("CLEAN"), sel))
        assertEquals(listOf("CLEAN"), resolver.narrowOwnTreeTags("other", listOf("CLEAN"), sel))
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
    fun `treeSelection 在未引用预设时白名单为空表`() {
        // D-TG-018：`null`（放行全部）语义已废除 —— 未引用预设取空表 ⇒ 与"空预设"同效（全禁）
        val none = resolver.treeSelection(false, mapOf("CLEAN" to setOf("treeA")), emptyMap())
        assertTrue("未引用预设 ⇒ 白名单为空表（不是 null）", none.presetKeepByTag.isEmpty())

        val referenced = resolver.treeSelection(true, mapOf("CLEAN" to setOf("treeA")), emptyMap())
        assertEquals(mapOf("CLEAN" to setOf("treeA")), referenced.presetKeepByTag)
    }

    @Test
    fun `TreeSelection NONE 与未引用预设同效（用途树全不输出）`() {
        val none = DimensionItemResolver.TreeSelection.NONE
        assertTrue(none.presetKeepByTag.isEmpty())
        assertEquals(emptyList<String>(), resolver.narrowTreeTags("treeA", listOf("CLEAN"), none))
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
    fun `声明未写的字段回落全局规则行`() {
        val rule = resolver.toRule("CLEAN", TimingOverride(defaultStage = "LATE"), fallback = rule())
        assertEquals(UseStage.LATE, rule.defaultStage)
        assertEquals("未声明的 N 应回落全局", 1, rule.defaultSurplusIdleThreshold)
        assertEquals(1.0, rule.defaultOrderWeight, 0.0)
        assertEquals("未声明的 priority 应回落全局", 300, rule.priority)
    }

    @Test
    fun `priority 可在声明里可选覆盖`() {
        val rule = resolver.toRule("CLEAN", TimingOverride(priority = 999), fallback = rule())
        assertEquals(999, rule.priority)
    }

    @Test
    fun `无全局规则行时用内置默认`() {
        // 不是兜底：只在"用途已被声明、但字段没填"时落位（D-TG-018）
        val rule = resolver.toRule("FINISH", TimingOverride(defaultStage = "MID"), fallback = null)
        assertEquals(UseStage.MID, rule.defaultStage)
        assertEquals("内置默认 priority", 100, rule.priority)
        assertNull("内置默认 N = null", rule.defaultSurplusIdleThreshold)
        assertFalse("内置默认 replan = false", rule.defaultReplanAfterUse)
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
    fun `增量项只改其它字段时预设声明的 priority 不被丢弃`() {
        // 回归：mergeTiming 曾漏合并 priority ⇒ 增量项一碰该用途，预设声明的 priority 就静默回落全局行
        val merged = resolver.mergeTiming(
            lower = TimingOverride(priority = 999, defaultStage = "LATE"),
            upper = TimingOverride(defaultOrderWeight = 5.0)
        )
        assertEquals("upper 未声明 priority ⇒ 保留 lower 的", 999, merged.priority)
        assertEquals(
            "upper 声明了 priority ⇒ 覆盖 lower", 7,
            resolver.mergeTiming(lower = TimingOverride(priority = 999), upper = TimingOverride(priority = 7)).priority
        )
    }

    /**
     * T-TG-029：惜售是**独立维度**，合并链独立于时序 ——
     * 只声明惜售的层也参与合并，且不因时序侧缺席而被丢弃。
     */
    @Test
    fun `惜售维度独立合并且上层优先`() {
        val presetOnly = resolver.mergeSurplus(lower = SurplusOverride(surplusIdleThreshold = ThresholdPatch(3)), upper = null)
        assertEquals(3, presetOnly.surplusIdleThreshold?.value)

        val upperWins = resolver.mergeSurplus(
            lower = SurplusOverride(surplusIdleThreshold = ThresholdPatch(3)),
            upper = SurplusOverride(surplusIdleThreshold = ThresholdPatch(null))
        )
        assertTrue("上层显式声明「无门槛」应压过下层", upperWins.surplusIdleThreshold != null)
        assertNull("且值为 null", upperWins.surplusIdleThreshold!!.value)

        assertTrue("两层都缺席 ⇒ 未声明", resolver.mergeSurplus(lower = null, upper = null).isEmpty)
    }

    @Test
    fun `两层声明都缺席时合并结果为未声明`() {
        val merged = resolver.mergeTiming(lower = null, upper = null)
        assertTrue("两层都没声明 ⇒ 空声明（调用方据此判定「未声明 = 无规则」）", merged.isEmpty)
    }

    @Test
    fun `K-TG-005 显式声明 null 可把 N 声明为无门槛`() {
        val rule = resolver.toRule(
            "CLEAN",
            TimingOverride(),
            SurplusOverride(surplusIdleThreshold = ThresholdPatch(null)),
            fallback = rule(threshold = 1)
        )
        assertNull("显式 null 应声明为「不设门槛」", rule.defaultSurplusIdleThreshold)
    }

    @Test
    fun `未出现门槛字段时回落全局 N`() {
        val rule = resolver.toRule("CLEAN", TimingOverride(defaultStage = "LATE"), fallback = rule(threshold = 1))
        assertEquals(1, rule.defaultSurplusIdleThreshold)
    }

    @Test
    fun `声明里的 stage 非法时回落而不炸`() {
        val rule = resolver.toRule("CLEAN", TimingOverride(defaultStage = "NOT_A_STAGE"), fallback = rule())
        assertEquals("非法 stage 应回落全局行的值", UseStage.MID, rule.defaultStage)
    }

    // ─────────────────────── payload 编解码（单点） ───────────────────────

    @Test
    fun `树 id 列表编解码可往返`() {
        val payload = DimensionPayloadCodec.encodeTreeIds(listOf("t2", "t1", "t1"))
        assertEquals(setOf("t1", "t2"), DimensionPayloadCodec.decodeTreeIds(payload))
        assertTrue(DimensionPayloadCodec.encodeTreeIds(emptyList()).contains("[]"))
    }

    @Test
    fun `惜售 payload 用键存在性区分未声明与显式 null`() {
        val cleared = DimensionPayloadCodec.decodeSurplus(
            DimensionPayloadCodec.encodeSurplus(SurplusOverride(surplusIdleThreshold = ThresholdPatch(null)))
        )
        assertTrue("键存在 ⇒ 已声明", cleared.surplusIdleThreshold != null)
        assertNull("值为 null ⇒ 无门槛", cleared.surplusIdleThreshold!!.value)

        val declared = DimensionPayloadCodec.decodeSurplus(
            DimensionPayloadCodec.encodeSurplus(SurplusOverride(surplusIdleThreshold = ThresholdPatch(4)))
        )
        assertEquals(4, declared.surplusIdleThreshold?.value)

        assertTrue("空声明不落库 ⇒ 解出即未声明", DimensionPayloadCodec.decodeSurplus("{}").isEmpty)
    }

    @Test
    fun `时序 payload 不再承载 N（T-TG-029 剥离）`() {
        val decoded = DimensionPayloadCodec.decodeTiming(
            DimensionPayloadCodec.encodeTiming(TimingOverride(defaultStage = "MID"))
        )
        assertEquals("MID", decoded.defaultStage)
        assertFalse("有字段声明 ⇒ 非空操作", decoded.isEmpty)
        assertTrue("未声明任何字段 ⇒ 空操作", DimensionPayloadCodec.decodeTiming("{}").isEmpty)
    }
}
