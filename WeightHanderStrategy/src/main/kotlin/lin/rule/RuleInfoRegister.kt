package lin.rule

import lin.bean.CardWeightInfo
import lin.config.ConfigDispatcher
import lin.lifecycle.LifecycleRegister
import lin.myLog
import lin.serviceLoader.weightRule.*
import lin.utils.serviceLoader.ServiceLoaderUtils
import lin.weightHandler.condition.bean.ConditionGroup
import lin.weightHandler.condition.config.GroupStrategyDao
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

/**
 * 目的 降低ConditionWeightHandler的复杂
 * [lin.weightHandler.condition.ConditionWeightHandler]
 */
class RuleInfoRegister(infos: Collection<CardWeightInfo>, val configDispatcher: ConfigDispatcher) : KoinComponent {
    val groupCondition: HashMap<String, RuleInfo> = hashMapOf()
    val weightGroupInfos = infos.groupBy { it.groupId }
    val lifecycleRegister = get<LifecycleRegister>()

    //支持的类型
    private val regisTypes = setOf(WeightCondition::class, IntentRuleInfo::class)
    val regisInfo = regisTypes.associateWith { mutableMapOf<Double, MutableList<RuleInfo>>() }.toMutableMap()

    init {
        ServiceLoaderUtils.loadServices(RuleInfo::class.java).forEach {
            groupCondition[it.id()] = it
        }
        myLog.info {
            "加载到的条件组id:${groupCondition.keys}"
        }
        val weightGroupInfos: List<ConditionGroup>? = loadConfig()
        weightGroupInfos?.let {
            it.forEach { conditionGroup -> parseConditionGroup(conditionGroup) }
        }


    }

    private fun loadConfig(): List<ConditionGroup>? {
        val groupStrategyDao: GroupStrategyDao by inject()
        return groupStrategyDao.getAll()
    }


    fun parseConditionGroup(conditionGroup: ConditionGroup) {
        val weightConditionId = conditionGroup.ruleId
        val weightCondition = groupCondition[weightConditionId]
        //获取到对应id条件实现
        weightCondition?.let {

            //从权重表获取绑定数据数据
            for (bindId in conditionGroup.bindId) {
                val weightGroupInfo = weightGroupInfos[bindId]
                if (weightGroupInfo == null) {
                    myLog.warn { "条件组需要绑定的数据没有在权重表找到,weight(bindId)为${conditionGroup.bindId[0]}" }
                    return
                }
            }

            //这里采用反射复制,为了简洁和快速实现 没有采用工厂模式
            val copyCondition = it.copy()
            //处理依赖
            if (processDep(copyCondition, conditionGroup)) {
                //冗余信息
                bind(copyCondition, conditionGroup.bindId.toList())
            }


        } ?: run {//没有对应条件id实现
            //todo
            val msg =
                "groupId=${conditionGroup.groupId},没有匹配到规则:${conditionGroup.ruleId}的条件信息"
            myLog.warn { msg }
            return

        }
    }

    private fun bind(ruleInfo: RuleInfo, bindGroupIds: List<Double>) {
        //绑定配置信息
        if (ruleInfo is ExtConfig) {
            val cardConfigs = ruleInfo.cardConfigs()
            configDispatcher.processUniformList(bindGroupIds, cardConfigs)
        }
        registerRule(bindGroupIds, ruleInfo)

        lifecycleRegister.register(ruleInfo)


    }

    // 注册一个 RuleInfo，并绑定到多个 infoId（Double）
    fun registerRule(infoIds: Collection<Double>, rule: RuleInfo) {
        val matchedType = regisTypes.find { it.java.isAssignableFrom(rule::class.java) }
            ?: throw IllegalArgumentException(
                "Rule ${rule::class} does not implement any registered type: $regisTypes"
            )

        for (id in infoIds) {
            regisInfo[matchedType]?.getOrPut(id) { mutableListOf() } += rule
        }
    }

    /**
     * 依赖也就是配置信息
     */
    private fun processDep(ruleInfo: RuleInfo, conditionGroup: ConditionGroup): Boolean {
        //组权重处理
        ruleInfo.groupWeight = conditionGroup.groupWeight
        ruleInfo.setUnCondWeight(conditionGroup.unConditionWeight)
        ruleInfo.setNum(conditionGroup.num ?: 0)

        if (ruleInfo is DepProcessor) {
            return ruleInfo.processAndVerify(conditionGroup, weightGroupInfos)
        }

        return true
    }

    //都是通过ServerLoader加载没有可能获取不到
    private fun RuleInfo.copy(): RuleInfo {
        val clazz = this::class.java
        //都是通过ServerLoader加载没有可能获取不到
        val primaryConstructor = clazz.getConstructor()
        return primaryConstructor.newInstance()
    }

    inline fun <reified T : RuleInfo> getRules(): Map<Double, List<T>> {
        // 永远返回空 Map，而不是 null
        return (regisInfo[T::class] as? Map<Double, MutableList<T>>) ?: emptyMap()
    }


}