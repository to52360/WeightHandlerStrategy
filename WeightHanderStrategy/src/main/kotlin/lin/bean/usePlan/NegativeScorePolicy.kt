package lin.bean.usePlan

/**
 * T-028：负分（ts<0）在第二轮余费填充中的语义策略。
 *
 * 控制 ts<0 的牌在余费填充 [lin.bean.passesSurplusGate] 中是否绕 N（余费门槛）优先填充。
 *
 * @property NORMAL（默认，推荐）ts<0 按无战术立场处理，尊重 N 惜售——N=0 折价补位 / N>0 惜售 held。
 *   **安全默认**，不会误垫 N>0 的惜售牌。
 * @property AGGRESSIVE ts<0 也绕 N，以 `E + ts×scale` 折价补位——不浪费剩余费，
 *   但可能覆盖 N>0 的惜售保护。仅少数确需「ts<0 也填」的卡显式配置。
 */
enum class NegativeScorePolicy {
    NORMAL,
    AGGRESSIVE
}