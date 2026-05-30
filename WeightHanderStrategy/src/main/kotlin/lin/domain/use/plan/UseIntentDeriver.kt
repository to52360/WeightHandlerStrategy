package lin.domain.use.plan

import lin.bean.usePlan.CardUseConfig
import lin.bean.usePlan.PurposeTag
import lin.bean.usePlan.UseIntent
import lin.bean.usePlan.UseStage


/**
 * 意图推导核心器。
 *
 * PurposeTag 只提供规则/评估侧的宏观用途信号。
 * 默认出牌阶段由这里统一推导；特例直接使用 stageOverride 指定。
 */
object UseIntentDeriver {
    /**
     * 根据配置推导运行时意图。
     *
     * stageOverride 优先级最高；没有显式指定时，才按用途标签推导默认阶段。
     */
    fun derive(config: CardUseConfig): UseIntent {
        val derivedStage = config.stageOverride ?: when {
            config.purposeTags.contains(PurposeTag.SAVE_LIFE) -> UseStage.SAVE_LIFE
            config.purposeTags.contains(PurposeTag.CLEAN) -> UseStage.CLEAN
            config.purposeTags.contains(PurposeTag.FINISH) -> UseStage.FINISH
            config.purposeTags.contains(PurposeTag.GREED) -> UseStage.SETUP
            else -> UseStage.VALUE
        }
        return UseIntent(
            stage = derivedStage,
            replanAfterUse = config.replanAfterUse,
            orderWeight = config.orderWeight
        )
    }
}

fun interface UseIntentProvider {
    /**
     * 根据卡牌所属的 CardGroupBinding.id 集合，生成新编排体系里的使用意图。
     * 当前 configUi 通过 SqliteUseIntentProvider 从 card_use_config 读取配置。
     */
    fun intentOf(cardGroupIds: Set<String>): UseIntent
}
