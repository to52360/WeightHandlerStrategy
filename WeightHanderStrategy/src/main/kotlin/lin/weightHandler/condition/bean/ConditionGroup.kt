package lin.weightHandler.condition.bean

import lin.domain.context.CostWeight


/**
 * [club.xiaojiawei.hsscriptcardsdk.bean.CardWeight.weight]整数部分条件组
 * 配置组权重信息位置
 * [lin.serviceLoader.weightRule.WeightCondition]
 * 生成位置
 * [lin.weightHandler.condition.config.GroupStrategyDao.getAll]
 * 设置位置
 * [lin.rule.RuleInfoRegister.processDep]
 *
 * 存储位置
 * [lin.serviceLoader.weightRule.utils.abs.AbsWeightCondition]
 */

 class ConditionGroup(
    val groupId: Int, //唯一标识
    val bindId: Array<Double>,
    val ruleId: String, //打出策略,依赖关联组 ,辅助类:核心卡没上手,依赖项:在手牌
    val depByWeightIds : Array<Double>,  //依赖权重数据
    weight: Double?, //基础优先度
    unConditionWeight: Double?,
    val num: Int?,
    val args: Map<String, Any> = emptyMap() // 存储 UI 或配置通过 Json 传入的动态属性值
){
    val groupWeight = weight ?: CostWeight
    val unConditionWeight = unConditionWeight ?: -groupWeight
 }


/**
 *
 *
 * 自定义加载配置性
 */
/* class ConditionByCustomize(
    val depId: Int,//组id
    val key : Int,
    groupId: Int,
    bindId: Double,
    ruleId: Int,
    groupWeight: Double,
     depByWeightId: Double
) :
    ConditionGroup(
        groupId,
        bindId,
        ruleId,
        groupWeight,
        depByWeightId
    )*/

