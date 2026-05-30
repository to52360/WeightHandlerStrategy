package lin.domain.use.plan

import lin.bean.usePlan.*


/**
 * 意图推导核心器。
 *
 * PurposeTag 只提供规则/评估侧的宏观用途信号，UseTag 只提供编排/执行侧的行为信号。
 * 这里是两类标签进入 UseStage 的唯一收口点，避免 rule 和排序器各自解释标签。
 */
object UseIntentDeriver {
    /**
     * 根据配置推导运行时意图。
     *
     * stageOverride 优先级最高；没有显式指定时，才按用途和执行标签推导默认阶段。
     */
    fun derive(config: CardUseConfig, orderWeight: Double = 0.0): UseIntent {
        val derivedStage = config.stageOverride ?: when {
            config.purposeTags.contains(PurposeTag.SAVE_LIFE) -> UseStage.SAVE_LIFE
            config.tags.contains(UseTag.RESOURCE) -> UseStage.RESOURCE
            config.tags.contains(UseTag.DRAW) -> UseStage.SETUP
            config.purposeTags.contains(PurposeTag.CLEAN) || config.tags.contains(UseTag.CLEAN) -> UseStage.CLEAN
            config.tags.contains(UseTag.COMBO_CORE) || config.tags.contains(UseTag.COMBO_DEP) -> UseStage.COMBO
            config.purposeTags.contains(PurposeTag.FINISH) -> UseStage.FINISH
            else -> UseStage.VALUE
        }
        return UseIntent(
            stage = derivedStage,
            tags = config.tags,
            orderWeight = orderWeight
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
