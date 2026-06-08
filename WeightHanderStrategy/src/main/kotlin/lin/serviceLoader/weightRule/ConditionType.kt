package lin.serviceLoader.weightRule


//减轻开发者所需知识 ,属于未分类区域,暂时放这



/**
 *墓场
 */
/*interface GraveyardArea : WeightCondition {
    override fun calculateSetWeight(callCard: ComboCard, warInfo: WarInfo)= onWarInfoProcessWeight(warInfo.getGraveyardCards())
    fun onWarInfoProcessWeight(graveyardCards: List<Card>)
}*/

interface DefaultWeightCondition : WeightCondition, DepWeightInfo




//可以一起打出
interface ComboCondition{

    //收益组默认之后打出
     // todo-future 之前也没有实现
    fun after() = true

}



