package lin.domain.use.plan

import lin.bean.usePlan.*


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

/**
 * 卡牌用途标签提供者（per cardId）。
 *
 * SPI 入口，configUi 通过此接口读取 card_purpose 表。
 * 引擎端提供默认实现返回空 CardPurpose。
 */
fun interface CardPurposeProvider {
    fun purposeOf(cardIds: Set<String>): Map<String, CardPurpose>
}

/**
 * 分组使用覆盖提供者（per groupId）。
 *
 * SPI 入口，configUi 通过此接口读取 group_use_override 表。
 * 引擎端提供默认实现返回空 Map。
 */
fun interface GroupUseOverrideProvider {
    fun overridesOf(groupIds: Set<String>): Map<String, GroupUseOverride>
}
