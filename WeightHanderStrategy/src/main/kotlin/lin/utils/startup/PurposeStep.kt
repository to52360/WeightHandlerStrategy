package lin.utils.startup

import lin.bean.usePlan.CardPurpose
import lin.serviceLoader.provider.CardPurposeProvider
import lin.utils.runCatchingLog
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 加载卡牌用途（Koin: CardPurposeProvider），失败则降级为空配置。
 */
class PurposeStep : ConfigBindingStep, KoinComponent {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        builder.cardPurposes = loadCardPurposes()
    }

    private fun loadCardPurposes(): Map<String, CardPurpose> {
        return runCatchingLog("加载 CardPurposeProvider 失败，使用空用途配置") {
            get<CardPurposeProvider>()
        }.getOrNull()?.findAllEnabled() ?: emptyMap()
    }
}
