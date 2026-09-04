package lin.ai.config

import lin.dao.CardGroupJsonParser
import lin.repository.CardDetail
import lin.repository.HsCardRepository
import lin.serviceLoader.cardInfoProvide.decodeCostValue

/**
 * CardGroupQueryService 的默认实现。
 * 从 .cardgroup 文件读取卡池结构，从 hs.cards 批量查询卡牌全属性。
 * 单一职责：只做卡池查询，不碰评估树或引擎逻辑。
 */
class DefaultCardGroupQueryService(
    private val hsCardRepository: HsCardRepository
) : CardGroupQueryService {

    override fun listCardGroupSources(): List<CardGroupSourceInfo> {
        return CardGroupJsonParser.loadAllCardGroups().map { (fileName, config) ->
            CardGroupSourceInfo(
                fileName = fileName,
                enabled = config.enabled,
                cardCount = config.cards.size
            )
        }
    }

    override fun getCardGroupDetail(fileName: String): CardGroupDetail? {
        val config = CardGroupJsonParser.loadByFileName(fileName) ?: return null
        val cardIds = config.cards.map { it.cardId }
        val cardMap: Map<String, CardDetail> = hsCardRepository.findCardDetailsByIds(cardIds)
            .associateBy { it.cardId }
        val cards = config.cards.map { weight ->
            val info = cardMap[weight.cardId]
            // D-007 v4（sop-rework T-002）：解码回显语义字段（等效费支持 0.5 档），
            // 不暴露「等效费 + 门槛挤一个数」的存储编码原值。
            val decoded = weight.powerWeight?.takeIf { it > 0.0 }?.let { decodeCostValue(it) }
            CardGroupCard(
                cardId = weight.cardId,
                name = info?.name ?: weight.cardId,
                text = info?.text,
                cost = info?.cost,
                type = info?.type,
                attack = info?.attack,
                health = info?.health,
                race = info?.race,
                cardClass = info?.cardClass,
                weight = weight.weight,
                changeWeight = weight.changeWeight,
                equivalentCost = decoded?.equivalentCostValue?.takeIf { it > 0.0 },
                surplusIdleThreshold = decoded?.surplusIdleThreshold
            )
        }
        return CardGroupDetail(fileName = fileName, cards = cards)
    }
}