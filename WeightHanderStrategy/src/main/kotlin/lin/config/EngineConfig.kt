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
    val useSkillWeight get() = store.double("scoring.use.skill.weight", -7.0)
    val scoreExponent get() = store.double("scoring.exponent", 1.0)
    val penaltyRatioExponent get() = store.double("scoring.penalty.ratio.exponent", 0.5)
    val penaltyWeight get() = store.double("scoring.penalty.weight", 0.35)
    val comboCardWeight get() = store.double("scoring.combo.card.weight", 0.1)
    val comboDecayFactor get() = store.double("scoring.combo.decay.factor", 0.85)

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
