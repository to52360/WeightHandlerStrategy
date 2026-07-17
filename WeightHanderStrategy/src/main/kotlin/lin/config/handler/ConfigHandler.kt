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

                is EvaluatorTreeRoot -> cardWeightInfos.forEach { info ->
                    info.addIntentEvaluatorRoot(config.root)
                }
            }
        }
    }
}
