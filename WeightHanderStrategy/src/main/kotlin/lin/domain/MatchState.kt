package lin.domain

import lin.bean.ComboCard
import lin.bean.purposeTagValues
import lin.domain.WarInfo
import lin.lifecycle.GameLifecycle
import lin.lifecycle.RoundLifecycle

/**
 * 跨回合对局状态容器（D-1）。
 *
 * 通用多维计数器：用 key 前缀区分统计维度（CARD / GROUP / PURPOSE）和周期（GAME / ROUND）。
 * - gameStats：整局累计，[GameLifecycle.start] 清空
 * - roundStats：回合累计，[RoundLifecycle.start] 清空
 * - currentTurnPlayedCards：本回合打出流水
 *
 * 读侧通过 [lin.rule.context.RuleEnv.matchState] 访问。
 */
class MatchState : GameLifecycle, RoundLifecycle {

    /** 统计维度键 */
    enum class StatDimensionKey { CARD, GROUP, PURPOSE }
    /** 统计周期 */
    enum class StatDuration { GAME, ROUND }
    /** 一条统计维度声明 */
    data class StatDimension(val key: StatDimensionKey, val duration: StatDuration)

    companion object {
        private const val KEY_CARD = "CARD"
        private const val KEY_GROUP = "GROUP"
        private const val KEY_PURPOSE = "PURPOSE"
    }

    // ── 内部存储 ──

    private val gameStats = mutableMapOf<String, Int>()
    private val roundStats = mutableMapOf<String, Int>()
    private val currentTurnPlayedCards = mutableListOf<String>()
    /** 整局已打出活动事件（Q-2a）。与维度注册解耦，[recordCardPlayed] 始终追加。 */
    private val playedEventsList = mutableListOf<MatchActivityEvent>()
    /** 打出事件版本号（Q-2a 缓存驱动）。每次 [recordCardPlayed] 递增，用于管道缓存失效。 */
    private var playEventVersion = 0

    /** 当前注册的统计维度（多分组去重并集） */
    private var currentDimensions = emptyList<StatDimension>()
    /** 预编译的记录闭包：构造时分派，运行时零分支 */
    private var recordLogic: (ComboCard) -> Unit = { _ -> }

    // ── 配置：声明统计维度（RecordPlayAction 首次出牌时懒注册） ──

    fun registerDimensions(dimensions: List<StatDimension>) {
        if (dimensions.isEmpty()) return
        currentDimensions = (currentDimensions + dimensions).distinct()
        recordLogic = buildRecordLogic()
    }

    private fun buildRecordLogic(): (ComboCard) -> Unit {
        val steps = currentDimensions.map { dim ->
            val write: (String) -> Unit = when (dim.duration) {
                StatDuration.GAME  -> { k -> gameStats[k] = (gameStats[k] ?: 0) + 1 }
                StatDuration.ROUND -> { k -> roundStats[k] = (roundStats[k] ?: 0) + 1 }
            }
            when (dim.key) {
                StatDimensionKey.CARD ->
                    { card: ComboCard -> write("$KEY_CARD:${card.cardId()}") }
                StatDimensionKey.GROUP ->
                    { card: ComboCard -> card.groupIds().forEach { write("$KEY_GROUP:$it") } }
                StatDimensionKey.PURPOSE ->
                    { card: ComboCard -> card.purposeTagValues().forEach { write("$KEY_PURPOSE:$it") } }
            }
        }
        return { card -> steps.forEach { it(card) }; currentTurnPlayedCards += card.cardId() }
    }

    // ── 写入：运行时零分支 ──

    /** 记录一次打出（维度统计 + 事件追加）。事件记录与维度注册解耦，始终执行。 */
    fun recordCardPlayed(card: ComboCard) {
        recordLogic(card)
        playedEventsList += MatchActivityEvent(MatchActivityKind.CARD_PLAYED, card.cardId())
        playEventVersion++
    }

    // ── 本局查询（保持兼容） ──

    fun cardPlayedCount(cardId: String): Int = gameStats["$KEY_CARD:$cardId"] ?: 0
    fun groupPlayedCount(groupId: String): Int = gameStats["$KEY_GROUP:$groupId"] ?: 0
    fun allGroupPlayCounts(): Map<String, Int> = extractPrefix(gameStats, KEY_GROUP)
    fun currentTurnPlayed(): List<String> = currentTurnPlayedCards.toList()

    // ── 新增查询 ──

    /** 本局累计：purposeTagId 打出次数 */
    fun purposePlayedCount(tagValue: String): Int = gameStats["$KEY_PURPOSE:$tagValue"] ?: 0

    /** 本回合累计：cardId 打出次数 */
    fun roundCardPlayedCount(cardId: String): Int = roundStats["$KEY_CARD:$cardId"] ?: 0

    /** 本回合累计：groupId 打出次数 */
    fun roundGroupPlayedCount(groupId: String): Int = roundStats["$KEY_GROUP:$groupId"] ?: 0

    /** 本局全部打出活动事件（Q-2a 读侧） */
    fun playedEvents(): List<MatchActivityEvent> = playedEventsList.toList()

    /** 打出事件版本号（Q-2a 缓存驱动：[recordCardPlayed] 时递增）。 */
    fun playEventVersion(): Int = playEventVersion

    // ── 生命周期 ──

    override fun start() {
        gameStats.clear()
        roundStats.clear()
        currentTurnPlayedCards.clear()
        playedEventsList.clear()
        playEventVersion = 0
    }

    override fun start(warInfo: WarInfo) {
        roundStats.clear()
        currentTurnPlayedCards.clear()
    }

    // ── 工具 ──

    private fun extractPrefix(map: Map<String, Int>, prefix: String): Map<String, Int> =
        map.filterKeys { it.startsWith("$prefix:") }
            .mapKeys { it.key.removePrefix("$prefix:") }
}
