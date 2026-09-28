package lin.mcp.coverage

import lin.mcp.ConfigSnapshot
import lin.rule.tree.findOverride

/**
 * 全部覆盖来源（**声明式注册清单**：加一类覆盖语义，就在此加一行 + 写一个下面的函数）。
 *
 * 顺序 = 输出 `coverage` 视图的字段顺序（[associate] 保序），也是「先看什么」的默认阅读顺序：
 * 评估树 → combo → 光环 → 编排。
 *
 * 注册方式刻意是**类内直构造**，不进 Koin（`D-FO-009`：同类型 + 无条件符的容器列表会互相覆盖；
 * 这些来源也无需替身/生命周期 ⇒ 属"过程"，不是"服务"）。
 */
val coverageSources: List<CoverageSource> = listOf(
    CoverageSource("evaluatorTrees", CoverageKind.SCORING, ::evaluatorTreeEntries),
    CoverageSource("combo", CoverageKind.SCORING, ::comboEntries),
    CoverageSource("auraBoost", CoverageKind.SCORING, ::auraBoostEntries),
    CoverageSource("orchestration", CoverageKind.ORCHESTRATION, ::orchestrationEntries),
)

/** 评估树覆盖：GROUP 绑定到本分组的树摘要（`id` / `name`）。 */
fun evaluatorTreeEntries(bindingId: String, snap: ConfigSnapshot): List<Map<String, Any?>> =
    snap.treeByBinding[bindingId].orEmpty()

/** combo 覆盖：引用本分组为 core / dep 的 combo 计划（含关系与分值）。 */
fun comboEntries(bindingId: String, snap: ConfigSnapshot): List<Map<String, Any?>> =
    snap.comboByGroup[bindingId].orEmpty().map { (c, role) ->
        mapOf("id" to c.id, "relation" to c.relation, "role" to role, "score" to c.score)
    }

/** 光环覆盖：条件树内 `group_filter` 引用本分组的光环加分（`via` = 触发侧 / 受益侧）。 */
fun auraBoostEntries(bindingId: String, snap: ConfigSnapshot): List<Map<String, Any?>> =
    snap.boostByGroup[bindingId].orEmpty().map { (b, via) ->
        mapOf("id" to b.id, "name" to (b.name ?: ""), "via" to via, "score" to b.score)
    }

/**
 * 编排覆盖：分组级 stage 覆盖 / 条件 stage（只表达"怎么排"，故 [CoverageKind.ORCHESTRATION]）。
 *
 * 有则给一条（`stageOverride` 可为 null 而只有条件 stage），无则空列表；
 * 内容与分组视图顶部的 `stageOverride` / `conditionalStage` 字段同源（此处多给一份是让 status
 * 的依据在 `coverage` 里自证，避免"为什么是 ORCHESTRATED"再去翻字段）。
 */
fun orchestrationEntries(bindingId: String, snap: ConfigSnapshot): List<Map<String, Any?>> {
    val override = snap.bindingsById[bindingId]?.behaviors?.findOverride() ?: return emptyList()
    val stageOverride = override.stageOverride
    val conditionalStage = override.conditionalStage != null
    if (stageOverride == null && !conditionalStage) return emptyList()
    return listOf(
        mapOf("stageOverride" to stageOverride?.name, "conditionalStage" to conditionalStage)
    )
}
