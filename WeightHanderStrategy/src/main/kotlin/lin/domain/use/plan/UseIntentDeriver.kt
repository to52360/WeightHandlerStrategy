package lin.domain.use.plan

import lin.bean.usePlan.CardUseConfig
import lin.bean.usePlan.UseIntent
import lin.bean.usePlan.UseStage
import lin.bean.usePlan.UseTag

/**
 * 卡牌的使用意图配置（可与 DB/JSON 配置或 UI 直接绑定）。
 */


/**
 * 意图推导核心器（约定优于配置，简化 UI 输入）。
 */
object UseIntentDeriver {
    fun derive(config: CardUseConfig, orderWeight: Double = 0.0): UseIntent {
        val derivedStage = config.stageOverride ?: when {
            config.tags.contains(UseTag.RESOURCE) -> UseStage.RESOURCE
            config.tags.contains(UseTag.DRAW) -> UseStage.SETUP
            config.tags.contains(UseTag.CLEAN) -> UseStage.CLEAN
            config.tags.contains(UseTag.COMBO_CORE) -> UseStage.COMBO
            config.tags.contains(UseTag.COMBO_DEP) -> UseStage.COMBO
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
     * 第一版只声明边界，具体来源可以是 UI 配置、规则输出或手写配置。
     */
    fun intentOf(cardGroupIds: Set<String>): UseIntent
}

object TodoUseIntentProvider : UseIntentProvider {
    /**
     * 占位实现，防止新编排骨架提前绑定 DB、Koin 或旧 useGroupId 体系。
     */
    override fun intentOf(cardGroupIds: Set<String>): UseIntent {
        TODO("从 CardGroupBinding.id 或规则配置生成 UseIntent")
    }
}
