package lin.bean.usePlan

/**
 * 卡牌候选参与策略（静态配置层，非评估树输出）。
 *
 * 决定一张牌「能否进入哪一轮候选」：第一轮主组合搜索 / 第二轮余费补充。
 * - 一张卡一个值（互斥），由 `GroupUseOverride.candidatePolicy` > `CardPurpose.candidatePolicy`
 *   > 唯一声明 `defaultCandidatePolicy` 的用途标签默认值 > `NORMAL` 推导。
 * - 多标签声明互相冲突的候选策略属配置冲突，要求单卡/分组显式覆盖，不按 priority 裁决。
 * - 候选资格本身不改变 `UseStage` / `orderWeight` / `replanAfterUse`。
 */
enum class CandidatePolicy {
    /** 正常牌（身材/通用）：第一轮看总分，第二轮余费阶段也看总分 */
    NORMAL,

    /** 战术主导（解牌/针对牌/Combo/战吼）：战术命中（tacticalScore > 0）才进第一轮；余费阶段只看战术分，无战术不出 */
    TACTICS_DOMINANT,

    /** 余费专用（生命分流/转甲/摇图腾/低效过牌）：第一轮主动避让，第二轮余费打出 */
    SURPLUS_ONLY
}
