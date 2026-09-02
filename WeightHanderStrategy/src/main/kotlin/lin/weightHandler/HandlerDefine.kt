package lin.weightHandler

import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.domain.MyWarManage

interface WeightHandler : Priority {
    /**
     * todo-future 这里设计问题,不应该用基本类型作为返回值
     */
    fun cardWeightCompute(callCard: ComboCard, warManage: MyWarManage): Double


}

interface Priority {
    /**
     * 越大越后面执行
     */
    fun priority() = 100
}

/**
 * todo-future 看有没有必要,还没有考虑实现方案 之后权重处理器
 */
interface WeightHandlerAfter : Priority {
    fun afterCardWeightCompute(callCard: ComboCard, warManage: MyWarManage)
}
interface InitHandler{
    /**
     * @return 初始化结果, false将不加载Handler
     */
    fun init(infos:List<CardWeightInfo>)
}

interface DiscoverWeightHandler : Priority {
    fun cardWeight(comboCard: ComboCard):Double
}


