package lin.config

/** 引擎层配置：加键只在本文件加一行。 */
object EngineConfig {
    private val store = ConfigStore("engine.properties")

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

    init {
        require(penaltyWeight < costWeight) {
            "penaltyWeight ($penaltyWeight) 必须小于 costWeight ($costWeight)，否则会导致低费单卡严重负分偏见"
        }
    }
    // timing
    val awaitAnimationTime get() = store.long("timing.await.animation", 1000)
    val useAnimationTime get() = store.long("timing.use.animation", 1500)
    val changeAnimationTime get() = store.long("timing.change.animation", 2500)
}
