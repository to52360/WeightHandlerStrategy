package lin.utils.startup

import lin.serviceLoader.cardInfoProvide.CardWeightInfoProvide
import lin.utils.serviceLoader.ServiceLoaderUtils

/**
 * 加载基础权重 Map（SPI: CardWeightInfoProvide）。
 */
class WeightInfoStep : ConfigBindingStep {
    override fun contribute(builder: CardCombinedConfigBuilder) {
        ServiceLoaderUtils.loadServices(CardWeightInfoProvide::class.java).forEach { provider ->
            builder.baseInfos.putAll(provider.getInfos())
        }
    }
}
