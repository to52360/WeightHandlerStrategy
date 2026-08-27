package lin.bean.usePlan

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties

data class CardUseConfig(
    val purposeTags: Set<PurposeTagId> = emptySet(), // 战略用途：规则/评估语义，不表达真实出牌顺序
    val stageOverride: UseStage? = null,          // 默认留空由 UseIntentDeriver 推导，特例时手动指定
    val replanAfterUse: Boolean = false,          // 使用后是否需要重新规划
    val orderWeight: Double = 0.0                 // 同阶段内的人工排序偏好
)

/**
 * 运行时的出牌意图，由排序器和执行器消费。
 *
 * UsePlanOrderer 只消费 stage/orderWeight。
 * 执行后重规划这类行为用显式字段表达，不再通过标签间接解释。
 */
data class UseIntent(
    val stage: UseStage = UseStage.GENERAL,
    val replanAfterUse: Boolean = false,
    val orderWeight: Double = 0.0,
    val tagDefaultSurplusIdleThreshold: Int? = null, // T-026：tag 推导的默认余费门槛 N，null = 未声明回落 0
    val negativeScorePolicy: NegativeScorePolicy = NegativeScorePolicy.NORMAL // T-028：ts<0 在第二轮的语义策略，默认保守
)

/**
 * 卡牌自身的使用用途属性（per cardId）。
 *
 * 事实来源独立于分组，由 CardPurposeProvider 提供。
 * purposeTags 描述"为什么选"，不表达真实出牌顺序。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CardPurpose(
    val purposeTags: Set<PurposeTagId> = emptySet(),
    val replanAfterUse: Boolean = false
) {
    @JsonIgnore
    fun isDefault(): Boolean = this == EMPTY

    companion object {
        val EMPTY = CardPurpose()
    }
}

/**
 * 条件化阶段覆盖：运行期条件命中时使用 [stage]，未命中回落 [elseStage]（null = 沿用默认推导）。
 *
 * 条件引用 condition_tree_config 的 id（复用条件树：正交管道叶子 + And/Or/Branch 组合）。
 * 本消费方无参数通道，运行期由 GuardCompiler.compileTree 用条件树内参数裸编译（D-007 语义 B）。
 * 运行期由 UsePlanBuilder 用 RuleEnv 求值，产出动态 UseIntent；UsePlanOrderer 纯函数排序器不感知。
 */
data class ConditionalStageOverride(
    val conditionId: String,
    val stage: UseStage,
    val elseStage: UseStage? = null
)

/**
 * 分组级使用配置重载（per groupId）。
 *
 * 所有字段可空，null 表示"不覆盖"该维度，启动期合并时保留下层默认值。
 * 由 GroupUseOverrideProvider 提供。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GroupUseOverride(
    val stageOverride: UseStage? = null,
    val replanAfterUse: Boolean? = null,
    val orderWeight: Double? = null,
    val conditionalStage: ConditionalStageOverride? = null
) {
    @JsonIgnore
    fun isDefault(): Boolean = this == EMPTY

    companion object {
        val EMPTY = GroupUseOverride()
    }
}
