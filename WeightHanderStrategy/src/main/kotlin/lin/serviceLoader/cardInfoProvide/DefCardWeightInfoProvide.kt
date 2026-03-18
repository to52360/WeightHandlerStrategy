package lin.serviceLoader.cardInfoProvide


import club.xiaojiawei.hsscriptcardsdk.data.CARD_DATA_TRIE

import lin.bean.CardWeightInfo

class DefCardWeightInfoProvide : CardWeightInfoProvide {
    override fun getInfos(): Map<String, CardWeightInfo> {
        val weightConfigs = CARD_DATA_TRIE.data()
        return weightConfigs.associateBy(
            keySelector = { it.key }
        ) { weightCard ->
            val card = weightCard.value
            CardWeightInfo(weightCard.key, card.powerWeight, card.weight, card.changeWeight)
        }
    }
}