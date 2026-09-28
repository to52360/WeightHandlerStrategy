package lin.mcp

import com.fasterxml.jackson.annotation.JsonPropertyDescription
import lin.mcp.diagnostics.DiagnosticCheck
import lin.mcp.diagnostics.DiagnosticContext
import lin.mcp.diagnostics.DiagnosticResult
import lin.mcp.diagnostics.ManagerScope
import lin.mcp.diagnostics.surplusFeasibilityCheck
import lin.repository.HsCardRepository
import lin.rule.tree.findSurplusGate
import lin.serviceLoader.cardInfoProvide.decodeCostValue
import lin.serviceLoader.provider.PurposeTagIntentRuleProvider

/**
 * 策略**体检**工具（`T-FO-019` / `Q-FO-004` 层 1）：只读、只提示，答「机制对不对」。
 *
 * 与 `strategy_coverage` 的分工（刻意分家，不混一个工具）：
 * - `strategy_coverage`：**覆盖** —— 有没有机制（COVERED / ORCHESTRATED / UNCOVERED、裸卡清单）；
 * - `strategy_diagnostics`：**体检** —— 机制的值对不对 / 能不能满足 / 引用是否完整。
 *
 * ## 扩展点（加一项体检 = 加一行）
 * 见 [DiagnosticCheck]：实现一个 check 类，塞进 [checks] 清单即可；工具壳只做「装配快照 → 逐项执行 →
 * 汇总输出」，不感知任何体检项细节。本批已落第一项 [SurplusFeasibilityCheck]。
 *
 * ## 职责边界
 * - 只读，**零行为变更**，不阻断任何保存（写侧校验见 `SurplusGateValidator` 的提示通道）。
 * - 单点装配 [DiagnosticContext]（各 check 共享，避免各读一遍库）；单项失败不拖垮其他项（`runCatching`）。
 * - 输出**必须**带 `caveats`（近似边界）——本工具的全部结论都建立在"配置面 + 读侧近似解析"之上。
 */
class StrategyDiagnosticsToolProvider(
    private val assembler: ConfigSnapshotAssembler,
    private val purposeTagIntentRuleProvider: PurposeTagIntentRuleProvider,
    private val cardRepo: HsCardRepository
) : McpToolProvider {

    /** 体检项清单（扩展点：加一行即接入；函数引用直接当值用，见 [DiagnosticCheck] 的值化说明）。 */
    private val checks: List<DiagnosticCheck> = listOf(
        DiagnosticCheck("surplusFeasibility", "余费门槛可满足性（按生效 N）", ::surplusFeasibilityCheck),
    )

    override fun provide(): List<McpToolHandler> = listOf(
        typedTool<StrategyDiagnosticsInput>(
            name = "strategy_diagnostics",
            description = """
                策略配置体检（只读、只提示，不改任何配置）。答「机制**对不对**」——与 strategy_coverage
                （答「有没有机制」）互补：覆盖率不等于机制自洽。

                当前体检项（用 checks 参数选择，不传 = 全部）：
                - surplusFeasibility：**生效余费门槛可满足性**。逐卡遍历 逐卡 → 分组 → 标签 解析链后的**生效 N**，
                  判断「数据库初始费 + N ≤ 法力上限」；超出者列出（该牌未命中战术时可能永远垫不出）。
                  输出每条含 cardId / 初始费 / 生效 N / **生效来源**（哪一层给的 N），便于直接改配置。

                ⚠️ 全部结论为**读侧近似**（每项自带 caveats 会原样返回）：判据用数据库初始费而非引擎的实时费
                （减费卡可能实际可满足）、不含谓词组动态成员、标签层取当前启用卡组的合并结果。
                ⇒ 只作排查线索，不代替实战观察。

                managerId 不传 = 只扫**当前启用的卡组**（标签层只有对它才准确）；
                传其他卡组 id 时标签层为近似值（caveats 会说明）。
            """.trimIndent()
        ) { input ->
            val ctx = buildContext(input.managerId)
            val selected = checks.filter { input.checks.isNullOrEmpty() || it.id in input.checks }
            val results = selected.map { check ->
                check to runCatching { check.run(ctx) }.getOrElse { e ->
                    DiagnosticResult(
                        summary = mapOf("failed" to (e.message ?: e.toString())),
                        items = emptyList()
                    )
                }
            }
            mcpSuccess(
                mapOf(
                    "scope" to mapOf(
                        "managers" to ctx.managers.map {
                            mapOf(
                                "managerId" to it.managerId,
                                "managerName" to it.managerName,
                                "sourceFile" to it.sourceFile
                            )
                        },
                        "scannedCards" to ctx.cardIdsInScope().size,
                        "note" to "读侧近似：配置面 + 静态成员；详见各 check 的 caveats"
                    ),
                    "checks" to results.map { (check, result) ->
                        mapOf(
                            "id" to check.id,
                            "title" to check.title,
                            "summary" to result.summary,
                            "caveats" to result.caveats,
                            "items" to result.items
                        )
                    }
                )
            )
        }
    )

    /**
     * 装配只读体检上下文（**单点**）：N 解析链三层各读一次，供全部体检项共享。
     *
     * 配置来源走 [ConfigSnapshotAssembler]（与 `strategy_coverage` 同一份装配代码，避免两套读法各自漂移）。
     *
     * @param managerId 不传 = 当前启用卡组（标签层只有对它准确）；传值 = 该卡组
     */
    private fun buildContext(managerId: String?): DiagnosticContext {
        val snap = assembler.assemble(managerId)

        val scoped = if (managerId != null) {
            snap.managers.filter { it.cardGroupManagerId == managerId }
        } else {
            snap.managers.filter { snap.managerMeta[it.cardGroupManagerId]?.enabled == true }
        }

        val poolNByCard = mutableMapOf<String, Int>()
        val bindingNByCard = mutableMapOf<String, Int>()
        val bindingNameByCard = mutableMapOf<String, String>()

        val scopes = scoped.map { mgr ->
            val meta = snap.managerMeta[mgr.cardGroupManagerId]
            val poolCards = meta?.sourceFile?.let { snap.cardPools[it] }?.cards.orEmpty()
            // ① 逐卡层 N：.cardgroup 的 powerWeight v4 解码（百分位 = 门槛；0/null = 未声明）
            poolCards.forEach { card ->
                val n = card.powerWeight?.let { decodeCostValue(it).surplusIdleThreshold } ?: return@forEach
                if (n > 0) poolNByCard[card.cardId] = n
            }
            // ② 分组层 N：静态成员展开（谓词组 cardIds 为空 ⇒ 天然跳过）；同卡多组时首个给值者胜（近似）
            mgr.bindings.forEach { binding ->
                val gate = binding.behaviors.findSurplusGate()?.idleThreshold ?: return@forEach
                binding.cardIds.forEach { cardId ->
                    if (cardId !in bindingNByCard) {
                        bindingNByCard[cardId] = gate
                        bindingNameByCard[cardId] = binding.name
                    }
                }
            }
            ManagerScope(
                managerId = mgr.cardGroupManagerId,
                managerName = mgr.name,
                sourceFile = meta?.sourceFile ?: "",
                cardIds = (poolCards.map { it.cardId } + mgr.bindings.flatMap { it.cardIds }).distinct()
            )
        }

        // ③ 标签层 N：当前启用卡组上下文下**合并后**的规则（未声明 = 无规则 = N 不生效，与 D-TG-018 同构）
        val tagNByRule = purposeTagIntentRuleProvider.rules()
            .mapNotNull { rule -> rule.defaultSurplusIdleThreshold?.let { rule.tagId.value to it } }
            .toMap()
        val tagNByCard = snap.tagsByCard.mapValues { (_, tags) ->
            // 多标签取 max（与引擎 UseIntentDeriver 的保守方向合并一致）
            tags.mapNotNull { tagNByRule[it] }.maxOrNull() ?: 0
        }.filterValues { it > 0 }

        return DiagnosticContext(
            managers = scopes,
            poolNByCard = poolNByCard,
            bindingNByCard = bindingNByCard,
            bindingNameByCard = bindingNameByCard,
            tagNByCard = tagNByCard,
            tagsByCard = snap.tagsByCard,
            cardRepo = cardRepo
        )
    }
}

private data class StrategyDiagnosticsInput(
    @field:JsonPropertyDescription("可选：卡组 managerId。不传 = 当前启用的卡组（标签层只有对它准确）；传其他卡组时标签层为近似值。")
    val managerId: String? = null,
    @field:JsonPropertyDescription("可选：只跑指定体检项 id（如 [\"surplusFeasibility\"]）。不传 = 全部。可用值见工具描述。")
    val checks: List<String>? = null
)
