import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.util.CardDBUtil
import lin.bean.CardWeightInfo
import lin.bean.ComboCard

class 内联测试 {
}


 fun parse(key: String): CardRaceEnum {
    CardDBUtil.queryCardById(key).let {
        return if (it.isNotEmpty()) {
            CardRaceEnum.fromString(it.first().type)
        } else {
            CardRaceEnum.UNKNOWN
        }

    }
}
 fun getCache(cardWeightInfoList: List<CardWeightInfo>): List<CardRaceEnum> {
    var cache: List<CardRaceEnum> = emptyList()
    //select 空判断应该可以去掉,我前面已经判断过,这样的话要限制可见性
    if (cardWeightInfoList.isNotEmpty()) {
        cache = cardWeightInfoList.map {
            parse(it.cardId)
        }
    }
    return cache
}
fun getFunction(cardWeightInfoList: List<CardWeightInfo>): (List<ComboCard>) -> Boolean {
    val cacheRace = getCache(cardWeightInfoList)
    return { comboCards ->
        comboCards.any {
            cacheRace.any { cardRaceEnum -> it.card.cardRace == cardRaceEnum }
        }
    }
}