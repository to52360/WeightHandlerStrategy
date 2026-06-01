package lin.bean


import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.domain.context.BaseWeight
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import lin.domain.use.UseAfterStrategy
import lin.domain.use.UseBeforeStrategy


typealias ComboRule = (ComboCard) -> Double


/**
 *
 * todo-future 1.为了快速实现,弄了个上帝类出来,有空再重构 2.为了方便使用弄了很多方法(应该用扩展方法去扩展)
 *
 */
class ComboCard(
    val combinedConfig: CardCombinedConfig? = null,
    val card: Card
) {

    val cardWeightInfo = combinedConfig?.weightInfo

    fun groupIds(): Set<String> = combinedConfig?.groupIds ?: emptySet()

    fun useIntent() = combinedConfig?.useIntent

    fun comboEntries() = combinedConfig?.comboEntries ?: emptyList()

    fun comboUseBindings() = combinedConfig?.comboUseBindings ?: emptyList()

    val combo = cardWeightInfo?.combos
    //指定目标
    var pointCard: Card? = null


    fun groupId() = cardWeightInfo?.groupId
    //换牌策略
    val changeComboRule
        get() = cardWeightInfo?.changeComboRule

    //基础信息
    fun hasGroup(groupId: String): Boolean = groupId in groupIds()
    fun hasAnyGroup(groupIds: Collection<String>): Boolean = groupIds.any { it in this.groupIds() }
    fun cardId() = card.cardId
    fun cost() = card.cost
    //select 暂定直接修改,缺点:状态修改到处是无法追踪,要验证状态变化将很复杂,

    val basePowerWeight = cardWeightInfo?.powerWeight ?: BaseWeight


    var useAfterStrategy: MutableList<UseAfterStrategy>? = cardWeightInfo?.useAfterStrategy

    var useBeforeStrategy: MutableList<UseBeforeStrategy>? = cardWeightInfo?.useBeforeStrategy
    //使用卡牌分组和排序
    var useGroupId: Int = cardWeightInfo?.useGroupId ?: DefUseGroupId

    //同组优先级
    var useGroupOrder: Double = basePowerWeight

    // 出牌权重 可能作为权重优先级
    val powerWeight: Double
        get() = basePowerWeight + extPowerWeight
    var extPowerWeight: Double = BaseWeight

    /**
     * 权重累加方法
     */
    fun addWeight(weight: Double) {
        extPowerWeight += weight
        //todo-future 临时方案 使用和权重不应该共用,遇到奥秘情况会吃亏,遇到有变化就会吃亏
        useGroupOrder += weight
    }

    fun cleanWeight() {
        extPowerWeight = BaseWeight
    }

    /**
     * todo-future   or条件判断,存在问题(需要严格的顺序),目前不想大改先这样
     */
    fun isBaseWeight(): Boolean {
        return powerWeight == BaseWeight
    }

    /**
     * 战场相关
     */
    fun toDie() = cardWeightInfo?.toDie ?: false




    //在同一组会增加权重
    fun comboAddWeight(comboCard: ComboCard): Double {
        var weight = NotWeight
        combo?.forEach {
            weight += it.comboProcess(this, comboCard)
        }
        return weight
    }


    /**
     * todo-future 还需引入策略(全局策略,组策略,卡策略,来解决能不能使用),什么情况卖,什么情况不卖
     * 暂时 小于0为不可使用
     */
    fun canUse(): Boolean = powerWeight >= NotWeight
    fun unUse() {
        extPowerWeight = UnUseWeight
    }
    fun isUnUse(): Boolean {
        val offer = 1
        //todo-future 负权重要最后使用的临时方案,但也引入存在多重语义问题
        if (extPowerWeight == UnUseWeight) return true
        if (powerWeight < NotWeight && useGroupId == DefUseGroupId) useGroupId = LastUseGroupId + offer
        return false
    }



    /**
     * 获取指定权重
     */
    fun getExpectWeight(expectWeight: Double): Double {
        return expectWeight - powerWeight
    }

    /**
     * todo-future  用于处理重新生成comboCard时候判断是否重复,重新生成没有这么复杂的逻辑,但是效率有问题
     *  这样操作其他比较会不会有问题?
     */
    override fun equals(other: Any?): Boolean {
        //select 比较cardId还是entityId,没有想清楚,先
        return other?.let {
            if (this === other) true
            else
                when (other) {
                    is ComboCard -> {
                        card.entityId == other.card.entityId
                    }

                    is Card -> card.entityId == other.entityId
                    else -> false
                }
        } ?: false
    }

    override fun hashCode(): Int {

        return card.entityId.hashCode() * 31
    }

    override fun toString(): String {
        if (card.entityName.startsWith("UNK"))
            return "{id=${cardId()},weight=${powerWeight},useGroupId=${useGroupId},useGroupOrder=${useGroupOrder}}"
        return "{id=${cardId()},name=${card.entityName},weight=${powerWeight},useGroupId=${useGroupId},useGroupOrder=${useGroupOrder}"
    }

}
