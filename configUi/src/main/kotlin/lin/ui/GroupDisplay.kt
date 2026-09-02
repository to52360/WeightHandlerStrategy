package lin.ui

import lin.rule.tree.CardGroupBinding
import lin.rule.tree.GroupMembership

/**
 * T-007：UI 侧「cardIds 失真」展示修复的共享辅助——显式区分成员类型，
 * 避免谓词组（cardIds 恒空）被误读成空静态组。全模块统一走这里，防止各面板标记漂移。
 */
object GroupDisplay {

    /**
     * 分组展示名：静态组显示纯名称；谓词组加「[谓词组]」标记（成员由条件树运行时判定，无显式卡列表）。
     */
    fun displayNameWithType(binding: CardGroupBinding): String = when (binding.membership) {
        is GroupMembership.Static -> binding.name
        is GroupMembership.Predicate -> "${binding.name} [谓词组]"
    }

    /**
     * 分组成员概要：静态组=卡数量；谓词组=「条件组」（不能显示 0 张——那是失真）。
     */
    fun memberSummary(binding: CardGroupBinding): String = when (binding.membership) {
        is GroupMembership.Static -> "${binding.cardIds.size} 张"
        is GroupMembership.Predicate -> "条件组"
    }
}
