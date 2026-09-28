package lin.mcp.coverage

import lin.mcp.ConfigSnapshot

/**
 * 覆盖来源的**语义类别**——决定它是否参与 `COVERED` 判定（**唯一**决定者，不再散在 if 里）。
 *
 * - [SCORING]：评分类机制（评估树 / combo / 光环）。命中 ⇒ `COVERED`。
 * - [ORCHESTRATION]：编排类机制（stage 覆盖 / 条件 stage，将来还有时序）。命中 ⇒ `ORCHESTRATED`
 *   （仅在无任何 SCORING 命中时才算数：会"值多少"比"怎么排"更本质）。
 */
enum class CoverageKind { SCORING, ORCHESTRATION }

/**
 * 一类覆盖来源（**值化扩展点**，与诊断侧 `DiagnosticCheck` 同款模式）。
 *
 * 加一类覆盖语义（例如用户举例的"时序"）= **加一个顶层函数 + 在 [coverageSources] 里加一行**：
 * 输出的 `coverage` 字段自动多一个 [key]、`status` 判定自动把它按 [kind] 计入 ——
 * 不再需要同时改「新增索引 / 新增 context 字段 / 新增 entries / 改 status / 改 description」5 处
 * （漏改任一处的旧形态 = **静默不生效**，这正是本方案要消灭的失效形态）。
 *
 * @param key 输出字段名（进 `coverage` 视图的键），如 `evaluatorTrees` / `combo` / `auraBoost` / `orchestration`
 * @param kind 参与 status 判定的类别（见 [CoverageKind]）
 * @param entriesFor 按分组 id 产出覆盖条目；无覆盖时返回**空列表**（不要返回 null）
 */
data class CoverageSource(
    val key: String,
    val kind: CoverageKind,
    val entriesFor: (String, ConfigSnapshot) -> List<Map<String, Any?>>
)
