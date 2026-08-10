package lin.config.handler


import lin.bean.CardWeightInfo
import lin.bean.MetadataKey
import lin.bean.addSafe
import lin.config.*
import kotlin.reflect.KClass

interface ConfigHandler<T : CardConfig> {
    val configType: KClass<out T>

    /**
     * todo-future cardWeightInfos 强耦合 有新的需求再一起改
     */
    fun processConfig(cardConfigs: List<T>, cardWeightInfos: List<CardWeightInfo>)
}

class UseConfigHandler : ConfigHandler<CardAttributeConfig> {
    override val configType: KClass<out CardAttributeConfig> = CardAttributeConfig::class
    override fun processConfig(cardConfigs: List<CardAttributeConfig>, cardWeightInfos: List<CardWeightInfo>) {
        cardConfigs.forEach { config ->
            when (config) {
                is UseConfig -> {
                    // useGroupId/useGroupOrder 仍经旧链路写 CardWeightInfo（用户暂不删）；
                    // useStrategyList 不再经 ConfigHandler，改由 ConfigBindingStep(GroupBehaviorStep) 装配进 combinedConfig.useStrategies
                    cardWeightInfos.forEach { info ->
                        config.useGroupId?.let { info.useGroupId = it }
                        config.useGroupOrder?.let { info.useGroupOrder = it }
                    }
                }

                is CardType -> {
                    cardWeightInfos.forEach { info ->
                        info.addCardType(config)
                    }
                }
                is CardWeightConfigurer -> { //通用修改,存在问题,但是避免太多类结构问题
                    cardWeightInfos.forEach { info ->
                        config(info)
                    }
                }

                is CardWeightContext<*> -> {
                    cardWeightInfos.forEach {
                        @Suppress("UNCHECKED_CAST")
                        val key = config.key as MetadataKey<Any>
                        it.cardContext.addSafe(key, config.value)
                    }
                }
            }
        }
    }
}

class RuleConfigHandler : ConfigHandler<Rule> {
    override val configType: KClass<out Rule> = Rule::class
    override fun processConfig(cardConfigs: List<Rule>, cardWeightInfos: List<CardWeightInfo>) {
        cardConfigs.forEach { config ->
            when (config) {
                is Rules -> cardWeightInfos.forEach { info ->
                    //还是复制一个
                    info.setWeightRules(config.rules.toMutableList())
                }

                // @defect purpose-tag-extension/K-001: addSafe 追加而非覆盖——PURPOSE_TAG 树是 additive 累加，
                // 与"全局兜底=fallback"语义冲突（GROUP+PURPOSE_TAG 同时绑定时双倍计分）。当前靠配置约定不在两层重复打分，
                // 仅 BAN(constraint) 场景天然无冲突；加分方向 tag 树案例出现前不再讨论。
                is EvaluatorTreeRoot -> cardWeightInfos.forEach { info ->
                    info.addIntentEvaluatorRoot(config.root)
                }
            }
        }
    }
}
