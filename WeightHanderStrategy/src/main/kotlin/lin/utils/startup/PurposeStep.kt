package lin.utils.startup

import lin.bean.usePlan.CardPurpose
import lin.serviceLoader.cardInfoProvide.COINProvide
import lin.serviceLoader.provider.CardPurposeProvider
import lin.utils.runCatchingLog
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 加载卡牌用途（Koin: CardPurposeProvider），失败则降级为空配置；
 * 随后并入机制牌硬编码用途（[COINProvide.mechanismPurposes]，T-003）。
 */
class PurposeStep : ConfigBindingStep, KoinComponent {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        builder.cardPurposes = mergeMechanismPurposes(loadCardPurposes())
    }

    private fun loadCardPurposes(): Map<String, CardPurpose> {
        return runCatchingLog("加载 CardPurposeProvider 失败，使用空用途配置") {
            get<CardPurposeProvider>()
        }.getOrNull()?.findAllEnabled() ?: emptyMap()
    }

    companion object {
        /**
         * 机制牌用途并入用户配置：purposeTags 取并集（机制语义不可被用户配置删除），
         * replanAfterUse 等其余字段保留用户配置值。
         */
        fun mergeMechanismPurposes(
            userPurposes: Map<String, CardPurpose>,
            mechanismPurposes: Map<String, CardPurpose> = COINProvide.mechanismPurposes
        ): Map<String, CardPurpose> {
            if (mechanismPurposes.isEmpty()) return userPurposes
            return userPurposes.toMutableMap().apply {
                mechanismPurposes.forEach { (cardId, mechanism) ->
                    val merged = CardPurpose(
                        purposeTags = this[cardId].let { it?.purposeTags ?: emptySet() } + mechanism.purposeTags,
                        replanAfterUse = this[cardId]?.replanAfterUse ?: false
                    )
                    put(cardId, merged)
                }
            }
        }
    }
}
