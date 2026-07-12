package lin.domain

import lin.bean.ComboCard
import lin.domain.WarInfo
import lin.lifecycle.GameLifecycle
import lin.lifecycle.RoundLifecycle
import org.koin.core.component.KoinComponent

/**
 * 跨回合对局状态容器（D-1）。
 *
 * 存储整局累计的打出统计（cardId / groupId 维度），以及本回合打出事件列表。
 * - 整局统计在 [GameLifecycle.start]（对局开始）清空
 * - 本回合列表在 [RoundLifecycle.start]（每回合开始）清空
 *
 * 由 [MyWarManage] 创建、Koin 注册，并注册到生命周期。
 */
class MatchState : GameLifecycle, RoundLifecycle, KoinComponent {

    // 整局累计：cardId -> 打出次数
    private val cardPlayCount = mutableMapOf<String, Int>()

    // 整局累计：groupId -> 打出次数（一张卡计入其所有所属组）
    private val groupPlayCount = mutableMapOf<String, Int>()

    // 本回合打出事件列表（cardId 顺序），墓地不在此记录
    private val currentTurnPlayedCards = mutableListOf<String>()

    fun recordCardPlayed(card: ComboCard) {
        val cardId = card.cardId()
        cardPlayCount[cardId] = (cardPlayCount[cardId] ?: 0) + 1
        card.groupIds().forEach { gid ->
            groupPlayCount[gid] = (groupPlayCount[gid] ?: 0) + 1
        }
        currentTurnPlayedCards += cardId
    }

    fun cardPlayedCount(cardId: String): Int = cardPlayCount[cardId] ?: 0

    fun groupPlayedCount(groupId: String): Int = groupPlayCount[groupId] ?: 0

    fun currentTurnPlayed(): List<String> = currentTurnPlayedCards.toList()

    override fun start() {
        // 对局开始：清空整局累计 + 本回合列表
        cardPlayCount.clear()
        groupPlayCount.clear()
        currentTurnPlayedCards.clear()
    }

    override fun start(warInfo: WarInfo) {
        // 回合开始：仅清空本回合列表，保留整局累计
        currentTurnPlayedCards.clear()
    }
}
