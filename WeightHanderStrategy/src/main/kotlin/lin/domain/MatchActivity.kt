package lin.domain

/**
 * 对局活动事件类型（Q-2a 首批：打出 + 墓地，不含其他活动）。
 *
 * 仅暴露 [kind] 与 [cardId]，首批不做 groupIds。
 */
enum class MatchActivityKind {
    /** 卡牌打出 */
    CARD_PLAYED,
    /** 卡牌进入我方墓地（亡语/死亡） */
    CARD_GRAVEYARD
}

/**
 * 对局活动事件（Q-2a 事件流方案的最小数据单元）。
 *
 * 由 [MatchState] 记录打出事件，DataSource 从 [MatchState] 与墓地合成事件流，
 * 再由 Transform 按配置权重求和。
 *
 * @property kind  事件类型
 * @property cardId 关联卡牌 ID
 */
data class MatchActivityEvent(
    val kind: MatchActivityKind,
    val cardId: String
)
