package lin.bean


import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.domain.context.BaseWeight
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import lin.domain.use.UseAfterStrategy
import lin.domain.use.UseBeforeStrategy
import lin.domain.use.UseStrategy


typealias ComboRule = (ComboCard) -> Double


/**
 *
 * todo-future 1.为了快速实现,弄了个上帝类出来,有空再重构 2.为了方便使用弄了很多方法(应该用扩展方法去扩展)
 *
 * 防重复提醒：新增读取入口前，先全局搜 `fun ComboCard.xxx` 与 `ComboCard.xxx()` 是否已有实现。
 * 同一入口禁止同时保留「成员方法 + 扩展函数」两份——Kotlin 中成员方法会 shadow 同名扩展，
 * 触发 "extension is shadowed by a member" 警告（详见 ComboCard-Config-Access.md 的配置访问边界）。
 * 跨领域高频读取应放 ComboCardConfigAccess.kt 扩展；跨包消费 / 需特定可空语义才放成员方法。
 *
 */
class ComboCard(
    val combinedConfig: CardCombinedConfig? = null,
    val card: Card,
    // 基础分：由卡牌实际费用派生（baseScore(card.cost)），经 MyWarManage.parseComboCard 注入；
    // 已接入权重：basePowerWeight = baseScore + extraScore，powerWeight = basePowerWeight + extPowerWeight。勿重复加接。
    // 影响评估跟踪见 architecture-context/scoring-model/TRACKER.md 待决项 Q-5（baseScore 接入权重对出牌决策的影响，🔶 待评估）。
    val baseScore: Double = 0.0
) {

    val cardWeightInfo = combinedConfig?.weightInfo

    // 额外分（超模溢价）：原 powerWeight，无配置=0（见 scoring-model/TRACKER.md 三分量模型）
    val extraScore: Double = cardWeightInfo?.powerWeight ?: 0.0

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
    fun cardId() = card.cardId
    fun cost() = card.cost
    //select 暂定直接修改,缺点:状态修改到处是无法追踪,要验证状态变化将很复杂,
    // 静态分值（基础分 + 额外分），用作排序优先级与"是否基础卡"判定
    val basePowerWeight = baseScore + extraScore


    // 合并配置侧声明动作（combinedConfig.useStrategies），before/after 按类型分流；无数据则为 null，惰性创建避免空列表分配
    private inline fun <reified T : UseStrategy> mergeStrategies(): MutableList<T>? {
        val source = combinedConfig?.useStrategies ?: return null
        var result: MutableList<T>? = null
        for (s in source) {
            if (s is T) {
                if (result == null) result = mutableListOf()
                result.add(s)
            }
        }
        return result
    }

    var useAfterStrategy: MutableList<UseAfterStrategy>? = mergeStrategies()
    var useBeforeStrategy: MutableList<UseBeforeStrategy>? = mergeStrategies()
    //使用卡牌分组和排序
    var useGroupId: Int = cardWeightInfo?.useGroupId ?: DefUseGroupId

    //同组优先级
    var useGroupOrder: Double = basePowerWeight

    // 出牌权重（最终决策依据）：powerWeight = basePowerWeight + extPowerWeight。
    // 其中 basePowerWeight = baseScore + extraScore，故 baseScore（费用派生基础分）已通过 basePowerWeight
    // 参与最终出牌决策排序，并被 canUse() / isBaseWeight() 用作判定依据。
    // ⚠️ 此变动（baseScore 接入权重）对出牌决策的实际影响尚未评估，改动前需先确认波及范围。
    // 影响评估跟踪见 architecture-context/scoring-model/TRACKER.md 待决项 Q-5（🔶 待评估）。
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
     * 判断当前是否处于"基础分"状态：extPowerWeight 尚未被任何规则 addWeight 加分。
     * baseScore/extraScore 是构造时注入的基础分，不属于"规则加分"，故只看 extPowerWeight。
     * 通用随从兜底模型（GeneralMinionWeightHandler）据此判断"是否还需给身材兜底分"。
     */
    fun isBaseWeight(): Boolean {
        return extPowerWeight == BaseWeight
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
