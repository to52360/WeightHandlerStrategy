package lin.rule.orthogonal

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import lin.bean.ComboCard
import lin.domain.MatchActivityKind
import lin.rule.context.WarView
import lin.warExt.my.attack.getGraveyardCards
import lin.warExt.my.base.getHandCards
import lin.warExt.my.base.getPlayCards
import lin.warExt.my.base.meBlood
import lin.warExt.my.base.resource
import lin.warExt.rival.rivalCardsByPlayArea

/**
 * 战场局势视图数据源：输出 WarView 战场全局局势快照
 */
val WarViewSource = dataSource<WarView>(
    id = "war_view",
    name = "战场局势快照",
    description = "获取当前对局战场的全局局势视图快照（包含我方/敌方随从快照、血量、溢出伤害及可承受攻击上限）",
    categories = setOf(OrthogonalCategoryCatalog.BOARD.id, OrthogonalCategoryCatalog.GAME_STATE.id)
) { env ->
    env.warView()
}

// ==========================================
// 默认数据源 (DataSources)
// ==========================================

/**
 * 我方战场随从数据源
 */
val MeBoardCardsSource = dataSource<List<Card>>(
    id = "me_board_cards",
    name = "我方战场随从",
    description = "获取我方战场区域的随从卡牌列表",
    categories = setOf(OrthogonalCategoryCatalog.BOARD.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    env.warInfo().getPlayCards()
}

/**
 * 敌方战场随从数据源
 */
val RivalBoardCardsSource = dataSource<List<Card>>(
    id = "rival_board_cards",
    name = "敌方战场随从",
    description = "获取敌方战场区域的随从卡牌列表",
    categories = setOf(OrthogonalCategoryCatalog.BOARD.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    env.warInfo().rivalCardsByPlayArea()
}

/**
 * 战场全部卡牌数据源：输出 List<Card>
 */
val BoardCardsSource = dataSource<List<Card>>(
    id = "board_cards",
    name = "全部战场随从",
    description = "获取战场上的全部随从卡牌列表",
    categories = setOf(OrthogonalCategoryCatalog.BOARD.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    env.warInfo().getPlayCards() + env.warInfo().rivalCardsByPlayArea()
}

/**
 * 手牌数据源：输出 List<Card>
 */
val HandCardsSource = dataSource<List<Card>>(
    id = "hand_cards",
    name = "手牌卡牌列表",
    description = "获取当前手牌中的卡牌列表",
    categories = setOf(OrthogonalCategoryCatalog.HAND.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    env.warInfo().getHandCards()
}

/**
 * 手牌 ComboCard 数据源：输出 List<ComboCard>（含权重分组信息）
 * 直接读取 WarInfo 预计算缓存，零额外开销
 */
val HandComboCardsSource = dataSource<List<ComboCard>>(
    id = "hand_combo_cards",
    name = "手牌ComboCard列表",
    description = "获取手牌的 ComboCard 列表（含权重分组信息，从预计算缓存读取）",
    categories = setOf(OrthogonalCategoryCatalog.HAND.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    env.warInfo().handComboCards
}

/**
 * 我方战场 ComboCard 数据源：输出 List<ComboCard>（含权重分组信息）
 * 直接读取 WarInfo 预计算缓存，零额外开销
 */
val MeComboCardsSource = dataSource<List<ComboCard>>(
    id = "me_combo_cards",
    name = "我方战场ComboCard",
    description = "获取我方战场的 ComboCard 列表（含权重分组信息，从预计算缓存读取）",
    categories = setOf(OrthogonalCategoryCatalog.BOARD.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    env.warInfo().playComboCards
}

/**
 * 我方墓地卡牌数据源：输出 List<Card>
 */
val MyGraveyardCardsSource = dataSource<List<Card>>(
    id = "my_graveyard_cards",
    name = "我方墓地卡牌",
    description = "获取我方墓地中已阵亡/打出的卡牌列表",
    categories = setOf(OrthogonalCategoryCatalog.GRAVEYARD.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    env.warInfo().getGraveyardCards()
}

/**
 * 我方英雄血量数据源：输出 Int
 */
val MyHeroHealthSource = dataSource<Int>(
    id = "my_hero_health",
    name = "我方英雄有效血量",
    description = "获取我方英雄当前的有效血量（生命值 + 护甲）",
    categories = setOf(OrthogonalCategoryCatalog.GAME_STATE.id, OrthogonalCategoryCatalog.HERO.id)
) { env ->
    env.warInfo().meBlood()
}

/**
 * 我方当前法力水晶数据源：输出 Int
 */
val MyManaCrystalSource = dataSource<Int>(
    id = "my_mana_crystal",
    name = "我方当前法力水晶",
    description = "获取我方当前可用法力水晶数量",
    categories = setOf(OrthogonalCategoryCatalog.GAME_STATE.id, OrthogonalCategoryCatalog.MANA.id)
) { env ->
    env.warInfo().resource()
}

/**
 * 当前评估卡牌数据源：输出 ComboCard
 */
val EvaluatingCardSource = dataSource<ComboCard>(
    id = "evaluating_card",
    name = "当前评估卡牌",
    description = "获取当前评估打分的目标 ComboCard",
    categories = setOf(OrthogonalCategoryCatalog.HAND.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    callCard
}

/**
 * 对局分组打出计数数据源（D-3）：输出 Map<groupId, count> 整局累计打出次数
 * 无参，通过 RuleEnv.matchState() 读取。配合 pick_group_count Transform 选取指定分组计数。
 */
val MatchGroupPlayedCountsSource = dataSource<Map<String, Int>>(
    id = "match_group_played_counts",
    name = "对局分组打出计数",
    description = "获取本局各分组累计打出次数（整局累计，输出 Map<groupId, count>）",
    categories = setOf(OrthogonalCategoryCatalog.GAME_STATE.id)
) { env ->
    env.matchState().allGroupPlayCounts()
}

/**
 * 对局活动事件数据源（Q-2a）：输出 Map<MatchActivityKind, List<Card>>（打出事件与墓地事件）。
 * 无参：从 MatchState.playedCards() 与 getGraveyardCards() 引用合成 Map 返回，零元素创建。
 *
 * ⚠️ 关联 @defect ai-config-validation/K-002（部分解决）：Source 已改为 Map<Kind, List<Card>> 引用返回，
 * 不再合并创建对象。但 Transform 仍内嵌事件类型匹配判定（按 kind 分派遍历），
 * 完整重构方向是拆为 played_events / graveyard_events 两个独立 Source + 独立管道。
 * 触发条件不变：再有 Transform 内嵌匹配判定的同类案例出现时启动完整拆分。
 */
// @defect ai-config-validation/K-002（部分解决）：Source 已改为 Map<Kind, List<Card>> 引用返回，
// 不再合并创建对象。但 Transform 仍内嵌事件类型匹配判定（按 kind 分派遍历），
// 完整重构方向是拆为 played_events / graveyard_events 两个独立 Source + 独立管道。
// 触发条件不变：再有 Transform 内嵌匹配判定的同类案例出现时启动完整拆分。
val MatchActivityEventsSource = dataSource<Map<MatchActivityKind, List<Card>>>(
    id = "match_activity_events",
    name = "对局活动事件",
    description = "获取本局已打出卡牌与墓地卡牌，按事件类型分组返回卡牌列表引用",
    categories = setOf(OrthogonalCategoryCatalog.GAME_STATE.id)
) { env ->
    mapOf(
        MatchActivityKind.CARD_PLAYED to env.matchState().playedCards(),
        MatchActivityKind.CARD_GRAVEYARD to env.warInfo().getGraveyardCards()
    )
}

/**
 * 对局当前回合数数据源（G-08）：输出 Int 当前真实回合数（从 MatchState 读取，超越 10 水晶上限）
 */
val MatchTurnCountSource = dataSource<Int>(
    id = "match_turn_count",
    name = "对局当前真实回合数",
    description = "获取当前对局的真实回合数",
    categories = setOf(OrthogonalCategoryCatalog.GAME_STATE.id)
) { env ->
    env.matchState().turnCount()
}

/**
 * 我方已装备武器数据源：输出 List<Card> 当前英雄已装备的武器卡牌列表（未装备时为空列表）
 */
val MyWeaponSource = dataSource<List<Card>>(
    id = "my_weapon",
    name = "我方装备的武器",
    description = "获取我方当前英雄已装备的武器卡牌列表（未装备时为空列表）",
    categories = setOf(OrthogonalCategoryCatalog.BOARD.id, OrthogonalCategoryCatalog.CARD.id)
) { env ->
    listOfNotNull(env.warInfo().war.me.playArea.weapon)
}
