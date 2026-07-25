package lin.ui.tree_config.strategy

import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import lin.rule.build.DynamicFieldOption
import lin.serviceLoader.provider.SelectOptionProvider

class CardRaceOptionProvider : SelectOptionProvider {
    override val dataSourceId: String = "card_races"

    override fun getOptions(): List<DynamicFieldOption> {
        return CardRaceEnum.entries
            .filter { it != CardRaceEnum.ALL && it != CardRaceEnum.UNKNOWN }
            .map { race ->
                val displayName = when (race.name) {
                    "TOTEM" -> "图腾"
                    "PET" -> "野兽"
                    "PIRATE" -> "海盗"
                    "DEMON" -> "恶魔"
                    "MECHANICAL" -> "机械"
                    "UNDEAD" -> "亡灵"
                    "DRAGON" -> "龙"
                    "ELEMENTAL" -> "元素"
                    "QUILBOAR" -> "野猪人"
                    "NAGA" -> "纳迦"
                    "DRAENEI" -> "德莱尼"
                    else -> race.name
                }
                DynamicFieldOption(label = displayName, value = race.name)
            }
    }
}
