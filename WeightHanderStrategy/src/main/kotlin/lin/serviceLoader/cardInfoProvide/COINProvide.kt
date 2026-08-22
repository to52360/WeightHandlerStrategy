package lin.serviceLoader.cardInfoProvide


import club.xiaojiawei.hsscriptcardsdk.data.COIN_CARD_ID
import lin.bean.COINGroupId
import lin.bean.CardWeightInfo
import lin.bean.MetadataKey
import lin.bean.addSafe


/**
 * 暂时共用脚本的权重信息,暂时无时间研究ui配置,导致只能硬编码
 */
class COINProvide : CardWeightInfoProvide {
    companion object {
        val coinKey = MetadataKey<Int>("COIN")
    }
    /**
     *  存在魔数
     */
    override fun getInfos(): Map<String, CardWeightInfo> {
        // 硬币基础价值=0（不占配置费用分），"额外费用 + 最后打"由 useGroupId=COINGroupId + cardContext[coinKey] 通道承载，
        // 不再用 -20.0 负权重污染 powerWeight 字段。
        val coin = CardWeightInfo(COIN_CARD_ID, 0.0)
        //标记快速查询
        coin.useGroupId = COINGroupId
        coin.cardContext = coin.cardContext.addSafe(coinKey, 1)
        return mapOf(coin.cardId to coin )
    }
}