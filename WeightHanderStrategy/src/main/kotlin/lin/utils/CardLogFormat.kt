package lin.utils

import lin.bean.ComboCard

/**
 * 对局日志的**卡牌文本格式化**单点（T-FO-011）。
 *
 * 背景（2026-09-22 实战日志复盘）：原来的日志直接拼 `ComboCard.toString()`，
 * 有三个不可分析问题——① `useGroupId`/`useGroupOrder` 刷屏（半死字段，判读无价值）；
 * ② 权重全精度（`4.998076211353316`）；③ 一批卡挤在一行、`toString()` 的非 UNK 分支
 * **缺闭合括号** ⇒ 整行不可解析（已一并修）。
 *
 * 约定：**每卡一行**（前置两空格缩进），可直接 grep / 逐行对齐 / 目视比对。
 * 归因类信息（为何落选）**不在此处推断**——那会把判定逻辑复制出第二份（K-TG-007 教训），
 * 需要归因时调用点把判定输入（如 keepCost / 门槛 N）写进骨架行，由读者推。
 */
object CardLogFormat {

    /** 权重/分值统一 2 位小数。 */
    fun fixed(value: Double): String = String.format("%.2f", value)

    /** 单卡文本：`GDB_726 斩星巨刃 c4 w=4.99`（UNK 名不显示，仅保留 id）。 */
    fun card(card: ComboCard): String {
        val id = card.cardId()
        val name = card.card.entityName.takeIf { it.isNotBlank() && !it.startsWith("UNK") }
        val label = if (name == null) id else "$id $name"
        return "$label c${card.cost()} w=${fixed(card.powerWeight)}"
    }

    /** 一批卡：每卡一行（缩进 2 空格）；空集输出 `(空)`，避免日志里"看着像漏了"。 */
    fun cards(cards: Iterable<ComboCard>): String {
        val lines = cards.map { "  ${card(it)}" }
        return if (lines.isEmpty()) "  (空)" else lines.joinToString("\n")
    }
}
