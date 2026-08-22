package lin.config

import lin.bean.CardWeightInfo
import lin.config.find.def.WeightInfoFinder
import lin.config.handler.ConfigHandler
import lin.myLog
import lin.serviceLoader.provider.BindInfoProvider
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.component.KoinComponent
import kotlin.reflect.KClass

/**
 * todo-future 分管配置,过度版(存放还是原来位置,逐渐分离)
 */
class ConfigDispatcher(
    handlers: List<ConfigHandler<*>>,
    bindInfoFind: List<WeightInfoFinder<*>>
) : KoinComponent {

    private val handlerMap: Map<KClass<*>, ConfigHandler<*>> =
        handlers.associateBy { it.configType }
    private val bindInfoFindMap: Map<KClass<out Any>, WeightInfoFinder<out Any>> =
        bindInfoFind.associateBy { it.targetType }
    init {
        ServiceLoaderUtils.loadServices(BindInfoProvider::class.java).forEach { bindInfoProvider ->
            bindInfoProvider.provide().forEach { bindInfo ->
                processUniformList(bindInfo.findKey, bindInfo.cardConfigs)
            }
        }

    }

    fun <T : CardConfig> getHandler(configType: KClass<T>): ConfigHandler<T>? {
        @Suppress("UNCHECKED_CAST")
        return handlerMap[configType] as? ConfigHandler<T>
    }

    /**
     * todo-future 暂时方案,不应该传cardWeightInfos,应该传什么还没想想清
     * 待定是分组List
     *
     */
    private fun dispatch(cardConfigs: List<CardConfig>, cardWeightInfos: List<CardWeightInfo>) {
        val buckets = handlerMap.keys.associateWith { mutableListOf<CardConfig>() }
        // 第一步：分类 —— 将 configs 按 configType 分桶
        for (config in cardConfigs) {
            var matched = false
            for (groupType in handlerMap.keys) {
                if (groupType.java.isInstance(config)) {
                    //存在不支持报错,导致运行
                    buckets[groupType]!!.add(config)
                    matched = true
                }
            }
            if (!matched) {
                myLog.warn { "No handler for: ${config::class.simpleName}" }
            }
        }
        // 第二步：消费 —— 对每个非空桶执行对应 handler
        for ((groupType, group) in buckets) {
            if (group.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST")
                (handlerMap[groupType] as ConfigHandler<CardConfig>).processConfig(group, cardWeightInfos)
            }
        }
    }

    /**
     * 查询获取绑定对象,当不直接依赖绑定数据,可以移动该逻辑到configHandler
     * [ConfigHandler]
     *  @param cardConfigs 需要与要与key一个同一个模块,方便后续拆分
     */
    fun processUniformList(ids: List<Any>, cardConfigs: List<CardConfig>) {
        val groupedByType: Map<KClass<out Any>, List<Any>> = ids.groupBy { it::class }
        val cardWeightInfos = mutableListOf<CardWeightInfo>()
        groupedByType.forEach { (kClass, items) ->
            bindInfoFindMap[kClass]?.let {
                @Suppress("UNCHECKED_CAST")
                val handler = it as WeightInfoFinder<Any>
                items.forEach { item ->
                    val findResult = handler.process(item)
                    if (findResult.isEmpty()) {
                        myLog.warn { "key:${item} not find handler  " }
                    } else {
                        cardWeightInfos.addAll(findResult)
                    }

                }
            } ?: run {
                myLog.warn { "不支持类型: $kClass" }
            }
        }
        // 按 cardId 去重，避免多个 finder 结果中包含同一 CardWeightInfo 导致重复绑定
        val distinctInfos = cardWeightInfos.distinctBy { it.cardId }
        dispatch(cardConfigs, distinctInfos)

    }

    /**
     * 优化方法：当 ids 统一类型、configs 统一类型时使用。
     * 跳过 groupBy 分组和 dispatch 分桶，直接按类型路由到对应 finder 和 handler。
     */
    fun <T : Any> processByType(ids: List<T>, cardConfigs: List<CardConfig>) {
        if (ids.isEmpty()) return

        // 1. 直接按 id 类型查 finder，跳过 groupBy
        val kClass = ids.first()::class
        val finder = bindInfoFindMap[kClass] ?: run {
            myLog.warn { "不支持类型: $kClass" }
            return
        }

        @Suppress("UNCHECKED_CAST")
        val typedFinder = finder as WeightInfoFinder<T>
        val cardWeightInfos = ids.flatMap { typedFinder.process(it) }
            .distinctBy { it.cardId }
        if (cardWeightInfos.isEmpty()) return

        // 2. 优化 dispatch：如果所有 config 同类型，直接查找 handler 跳过分桶
        if (cardConfigs.isNotEmpty()) {
            val firstType = cardConfigs.first()::class
            val directHandler = handlerMap[firstType]
            if (directHandler != null && cardConfigs.all { it::class == firstType }) {
                @Suppress("UNCHECKED_CAST")
                (directHandler as ConfigHandler<CardConfig>).processConfig(cardConfigs, cardWeightInfos)
                return
            }
        }
        dispatch(cardConfigs, cardWeightInfos)
    }


}

fun <T : Any> ConfigDispatcher.processMoreConfig(key: T, vararg cardConfig: CardConfig) {
    processByType(listOf(key), cardConfig.toList())
}
