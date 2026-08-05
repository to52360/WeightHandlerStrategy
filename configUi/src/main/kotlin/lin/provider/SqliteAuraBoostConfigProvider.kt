package lin.provider

import lin.bean.AuraBoostConfig
import lin.repository.aura_boost.AuraBoostConfigService
import lin.serviceLoader.provider.AuraBoostConfigProvider

/**
 * aura_boost 表的引擎侧 SPI 实现（aura-boost）。
 * 引擎 AuraBoostConfig 不含 managerId（归属在配置侧），映射时丢弃。
 */
class SqliteAuraBoostConfigProvider(
    private val service: AuraBoostConfigService
) : AuraBoostConfigProvider {
    override fun findAll(): List<AuraBoostConfig> =
        service.loadAll().map { entity ->
            AuraBoostConfig(
                id = entity.id,
                name = entity.name,
                conditionId = entity.conditionId,
                targetConditionId = entity.targetConditionId,
                score = entity.score
            )
        }
}
