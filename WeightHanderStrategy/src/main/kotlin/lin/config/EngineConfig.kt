package lin.config

import lin.bean.usePlan.UseStage

/** 引擎层配置：加键只在本文件加一行。 */
object EngineConfig {
    private val store = ConfigStore("engine.properties")

    /** Q-032：出牌阶段排序值配置键，供外部 properties / `-D` 覆盖。 */
    private const val STAGE_ORDER_KEY = "stage.order"

    /** T-037：兜底排序键（stage 与 orderWeight 都相同时的最后 tie-break）的载体与方向配置键。 */
    private const val ORDER_FALLBACK_KEY = "order.fallback.key"
    private const val ORDER_FALLBACK_DIRECTION = "order.fallback.direction"

    // scoring (双轨制轻量微调基准与安全惩罚)
    val costWeight get() = store.double("scoring.cost.weight", 0.5)
    val maxCostWeight get() = store.double("scoring.max.cost.weight", 1.0)
    val notWeight get() = store.double("scoring.not.weight", 0.0)
    val baseWeight get() = store.double("scoring.base.weight", 1.0)
    val orderWeight get() = store.double("scoring.order.weight", 1.0)
    val unUseWeight get() = store.double("scoring.unuse.weight", -100.0)
    val penaltyRatioExponent get() = store.double("scoring.penalty.ratio.exponent", 0.5)
    val penaltyWeight get() = store.double("scoring.penalty.weight", 0.35)
    val comboCardWeight get() = store.double("scoring.combo.card.weight", 0.1)

    // 费用价值凹函数：costValue(cost) = costValueWeight * cost^costValueExponent
    // （基础价值三分流共用：配置费用 / 随从等效费用 / 法术初始费用）
    val costValueWeight get() = store.double("scoring.cost.value.weight", 3.0)
    val costValueExponent get() = store.double("scoring.cost.value.exponent", 0.5)
    val costValueMaxCost get() = store.double("scoring.cost.value.maxCost", 10.0)

    // 法术兜底保守系数（法术无身材/不占场，空放负收益，应比同费随从更保守）
    val spellCostValueWeight get() = store.double("scoring.spell.cost.value.weight", 1.5)

    // D-007 双费数模型：评估树战术分(分)→费 的全局换算。fillValue = fallback + min(G, tacticalScore×scale)。
    // 唯一量纲标定常数（Q-019~021 缩水残余）：典型满命中树分 8~10 分 × 0.4 ≈ 3.2 费，覆盖常见 G≤3 饱和到满值。
    val tacticalScoreScale get() = store.double("scoring.tactical.score.scale", 0.4)

    // D-011 全局绝望规则 v2：前置（场面承压+手牌无战术牌）成立时，按血量阶梯降低余费门槛 N（地板 1）。
    // ladder 格式 `blood:delta` 逗号分隔（血量 < blood → 门槛减 delta，多档取最大），二值绝望是退化特例。
    // enabled 默认 false——阶梯档位是未实战校准的估值（@verify），校准后再考虑默认开。
    val surplusDespairEnabled get() = store.boolean("scoring.surplus.despair.enabled", false)
    val surplusDespairLadder get() = store.raw("scoring.surplus.despair.ladder") ?: "15:1,10:2,5:9"

    init {
        require(penaltyWeight < costWeight) {
            "penaltyWeight ($penaltyWeight) 必须小于 costWeight ($costWeight)，否则会导致低费单卡严重负分偏见"
        }
    }

    // Q-032 出牌阶段排序值：解耦「阶段语义」与「阶段先后顺序」。
    // 默认等于枚举 ordinal——阶段全序原本硬编码在 [lin.bean.usePlan.UseStage] 的声明顺序里，
    // 无法为不同卡组表达不同顺序（如控制卡组要 LATE < MID 保命优先，快攻要 MID < LATE 解场优先）。
    // 配置化后只改本项即可调整全局波段先后，无需改代码；未列出的阶段回落其 ordinal。
    // 格式 `阶段:排序值` 逗号分隔，排序值升序即出牌先后；可为负、可并列（并列则退化为段内权重竞争）。
    // 粒度说明：当前为**全局**一份（未做卡组级），待出现真实的多卡组顺序差异需求再扩展。
    val stageOrder
        get() = store.raw(STAGE_ORDER_KEY)
            ?.takeIf { it.isNotBlank() }
            ?: UseStage.entries.joinToString(",") { "${it.name}:${it.ordinal}" }

    // T-037 兜底排序键：stage 与 orderWeight 都分不出先后时的最后 tie-break，是同段内顺序的实际主导键。
    // 旧行为硬编码 powerWeight 降序——powerWeight 是选牌层混合评分（baseValue + 树分 + 光环 + legacy + BaseWeight），
    // 其中只有树分对「谁先出」有实质贡献，光环/legacy/常数是噪声（legacy 量级可能压倒一切）。
    // 载体（有序链，direction 作用于整条链）：
    //   tactical（默认）= tacticalScore → baseValue：战术层自动涌现，降低逐卡编排要求；ts 并列/为 0 退回价值锚。
    //   base = 只看 baseValue，最可复现。weight = powerWeight，旧行为，仅 A/B 回退通道。
    // 方向：desc（默认，价值高者先出，旧行为）/ asc（价值低者先出，保留剩余费用弹性）。
    // 两个旋钮即是校准手段——方向与战术层均未实战校准（@verify use-intent-model/K-001、K-002）。
    val orderFallbackKey get() = store.raw(ORDER_FALLBACK_KEY)?.takeIf { it.isNotBlank() }
    val orderFallbackDirection get() = store.raw(ORDER_FALLBACK_DIRECTION)?.takeIf { it.isNotBlank() }

    // timing
    val awaitAnimationTime get() = store.long("timing.await.animation", 1000)
    val useAnimationTime get() = store.long("timing.use.animation", 1500)
    val changeAnimationTime get() = store.long("timing.change.animation", 2500)
}
