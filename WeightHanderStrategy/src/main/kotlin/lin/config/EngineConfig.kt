package lin.config

/** 引擎层配置：加键只在本文件加一行。 */
object EngineConfig {
    private val store = ConfigStore("engine.properties")

    // scoring
    val costWeight get() = store.double("scoring.cost.weight", 5.0)
    val maxCostWeight get() = store.double("scoring.max.cost.weight", 10.0)
    val notWeight get() = store.double("scoring.not.weight", 0.0)
    val baseWeight get() = store.double("scoring.base.weight", 1.0)
    val orderWeight get() = store.double("scoring.order.weight", 1.0)
    val unUseWeight get() = store.double("scoring.unuse.weight", -100.0)
    val useSkillWeight get() = store.double("scoring.use.skill.weight", -7.0)
    val scoreExponent get() = store.double("scoring.exponent", 0.5)
    val penaltyRatioExponent get() = store.double("scoring.penalty.ratio.exponent", 0.5)
    // timing
    val awaitAnimationTime get() = store.long("timing.await.animation", 1000)
    val useAnimationTime get() = store.long("timing.use.animation", 1500)
    val changeAnimationTime get() = store.long("timing.change.animation", 2500)
}
