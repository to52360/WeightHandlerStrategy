package lin.rule.condition

import lin.serviceLoader.provider.ConditionRegistrationProvider

val maxCostCondition = ConditionBuilder.scalar(ConditionType.IntType)
    .id("max_cost")
    .metadata(name = "费用不高于", desc = "判断当前卡牌费用是否不高于给定阈值")
    .field("maxCost", "费用上限")
    .factory { maxCost ->
        {
            val cost = callCard.card.cost
            cost <= maxCost
        }
    }
    .build()

val raceWhitelistCondition = ConditionBuilder.list(ConditionType.StringType)
    .id("race_whitelist")
    .metadata(name = "种族白名单", desc = "判断当前卡牌种族是否在给定列表中")
    .field("raceIds", "种族") { select("race") }
    .factory { raceIds ->
        {
            val raceName = callCard.card.cardRace.name
            raceIds.contains(raceName)
        }
    }
    .build()

class DemoConditionProvider : ConditionRegistrationProvider {
    override fun getConditionRegistrations(): Collection<ConditionRegistration<*>> {
        return listOf(
            maxCostCondition,
            raceWhitelistCondition
        )
    }
}
