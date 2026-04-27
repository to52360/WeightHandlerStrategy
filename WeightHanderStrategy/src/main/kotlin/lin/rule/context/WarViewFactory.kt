package lin.rule.context

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.domain.WarInfo
import lin.warExt.my.base.getPlayCards
import lin.warExt.my.base.hero
import lin.warExt.my.base.meBlood
import lin.warExt.my.base.resource
import lin.warExt.rival.rivalCardsByPlayArea

/**
 * 战场视图：持有 warInfo 引用，按需懒加载
 * 不拷贝数据，warInfo 本身即离散时间点的快照
 */
class WarView(private val warInfo: WarInfo) {
    val rival: SideSnapshot by lazy { SideSnapshot.from(warInfo.rivalCardsByPlayArea()) }
    val me: SideSnapshot by lazy { SideSnapshot.from(warInfo.getPlayCards()) }
    val meBlood: Int by lazy { warInfo.meBlood() }
    val excessDamage: Int by lazy { computeExcessDamage(rival, me.taunt) }
    val ableAtcSum: Int by lazy { computeAbleAtcSum(warInfo.resource(), meBlood, warInfo.hero()?.health ?: 0) }

    companion object {
        const val ATTENTION_AVG_ATC = 2
    }
}

fun WarInfo.toWarView() = WarView(this)

// ── 私有计算函数 ─────────────────────────────────────────
//todo 是否私有还要再研究一下
private fun computeAbleAtcSum(resource: Int, meBlood: Int, heroHealth: Int): Int {
    if (heroHealth == 0) return 0
    val base = acceptableRivalAttack(resource)
    return meBlood.coerceAtMost(heroHealth) * base / heroHealth
}

private fun acceptableRivalAttack(mana: Int): Int {
    if (mana <= 0) return 0
    return when {
        mana <= 7 -> (1.5 * mana + 1).toInt()
        else -> (1.4 * mana + 1.2).toInt().coerceAtMost(15)
    }
}

private fun computeExcessDamage(rival: SideSnapshot, meTaunt: List<Card>): Int {
    if (rival.cards.isEmpty()) return 0
    if (meTaunt.isEmpty()) return rival.sumAtc

    val tauntNum = meTaunt.size
    if (tauntNum >= rival.cards.size) return 0

    val meTauntBlood = meTaunt.sumOf { it.blood() }
    if (meTauntBlood >= rival.sumAtc) return 0

    val sortedRivalAtc = rival.cards.map { it.atc }.sortedDescending()
    val attackingTauntAtc = sortedRivalAtc.take(tauntNum).sum()

    return if (meTauntBlood >= attackingTauntAtc) {
        sortedRivalAtc.drop(tauntNum).sum()
    } else {
        computeOverflowDamage(rival.cards, meTaunt)
    }
}

private fun computeOverflowDamage(rivalCards: List<Card>, meTaunt: List<Card>): Int {
    val tauntHp = meTaunt.map { it.blood() }.toMutableList().also { it.sort() }
    val attacks = rivalCards.map { it.atc }.sorted()
    var overflow = 0
    var tauntIndex = 0
    for (atk in attacks) {
        while (tauntIndex < tauntHp.size && tauntHp[tauntIndex] <= 0) tauntIndex++
        if (tauntIndex >= tauntHp.size) overflow += atk
        else tauntHp[tauntIndex] -= atk
    }
    return overflow
}
