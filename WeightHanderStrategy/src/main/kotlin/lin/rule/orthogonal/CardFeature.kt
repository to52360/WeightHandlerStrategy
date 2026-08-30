package lin.rule.orthogonal

import club.xiaojiawei.hsscriptcardsdk.bean.Card

/**
 * 卡牌特征枚举（T-031）：把 SDK 卡牌上的布尔特征抽象为统一的谓词枚举，
 * 供正交管道 `has_card_feature` 算子做集合级存在性判定。
 *
 * 每个特征对应 SDK `BaseCard` 上的一个布尔/数值字段（见 [matches]），
 * 新增特征只需在此加枚举值 + 实现 matches，全链路自动获得可选值列表（UI 侧 `card_features`）。
 *
 * 命名原则：用中文显示名，value 用 SDK 字段名的 snake_case 形式，保证可读性。
 */
enum class CardFeature(val displayName: String) {
    TAUNT("嘲讽"),
    DEATHRATTLE("亡语"),
    DIVINE_SHIELD("圣盾"),
    CHARGE("冲锋"),
    RUSH("突袭"),
    LIFESTEAL("吸血"),
    REBORN("复生"),
    WIND_FURY("风怒"),
    POISONOUS("剧毒"),
    STEALTH("潜行"),
    BATTLE_CRY("战吼"),
    DISCOVER("发现"),
    SPELL_POWER("法强加成"),
    FREEZE("冻结"),
    IMMUNE("免疫"),
    ELUSIVE("扰魔"),
    STARSHIP("星舰");

    /** 判定一张卡是否具备本特征（SPELL_POWER 用数值 > 0，其余用布尔字段）。 */
    fun matches(card: Card): Boolean = when (this) {
        TAUNT -> card.isTaunt
        DEATHRATTLE -> card.isDeathRattle
        DIVINE_SHIELD -> card.isDivineShield
        CHARGE -> card.isCharge
        RUSH -> card.isRush
        LIFESTEAL -> card.isLifesteal
        REBORN -> card.isReborn
        WIND_FURY -> card.isWindFury
        POISONOUS -> card.isPoisonous
        STEALTH -> card.isStealth
        BATTLE_CRY -> card.isBattlecry
        DISCOVER -> card.isDiscover
        SPELL_POWER -> card.spellPower > 0
        FREEZE -> card.isFrozen
        IMMUNE -> card.isImmune
        ELUSIVE -> card.isElusive
        STARSHIP -> card.isStarship
    }
}
