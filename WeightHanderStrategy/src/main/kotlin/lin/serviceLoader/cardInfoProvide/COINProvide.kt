package lin.serviceLoader.cardInfoProvide


import club.xiaojiawei.hsscriptcardsdk.data.COIN_CARD_ID
import lin.bean.CardWeightInfo
import lin.bean.MetadataKey
import lin.bean.addSafe
import lin.bean.usePlan.CardPurpose
import lin.bean.usePlan.PurposeTagId


/**
 * 硬币（机制牌）信息提供者：基础权重 + 额外费用数值 + 用途标签均硬编码，
 * 不依赖 DB 配置（机制语义不可被用户配置删除）。
 */
class COINProvide : CardWeightInfoProvide {
    companion object {
        val coinKey = MetadataKey<Int>("COIN")

        /**
         * T-003：机制牌用途注入表（由 PurposeStep 启动期并入 cardPurposes）。
         * EXTRA_COST 标签即「额外费用特殊查询」的路由声明——ExtCostStrategy 据此接管该牌
         * （双世界比较，出牌时机写死在 find 阶段先于组合，不进 UsePlanOrderer）。
         */
        val mechanismPurposes: Map<String, CardPurpose> = mapOf(
            COIN_CARD_ID to CardPurpose(purposeTags = setOf(PurposeTagId.EXTRA_COST))
        )
    }
    /**
     *  存在魔数
     */
    override fun getInfos(): Map<String, CardWeightInfo> {
        // 硬币基础价值=0（不占配置费用分）；额外费用数值由 cardContext[coinKey] 承载（识别已迁 EXTRA_COST 标签，T-004），
        // 不再用 -20.0 负权重污染 powerWeight 字段。
        val coin = CardWeightInfo(COIN_CARD_ID, 0.0)
        coin.cardContext = coin.cardContext.addSafe(coinKey, 1)
        return mapOf(coin.cardId to coin )
    }
}