package lin.provider

import lin.bean.AuraBoostConfig
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.card_group.CardGroupService
import lin.serviceLoader.provider.AuraBoostConfigProvider

/**
 * aura_boost 表的引擎侧 SPI 实现（aura-boost）。
 * 引擎 AuraBoostConfig 不含 managerId（归属在配置侧），映射时丢弃。
 *
 * 供给过滤（2026-08-08，Q-002）：aura_boost 是卡组级配置（AuraBoostConfigService KDoc），
 * 只提供「全局 boost（managerId 为 null）+ 归属已启用卡组的 boost」，
 * 归属被禁用卡组的 boost 不进引擎（避免其他卡组光环误生效 + 削减引擎 boost 数 N）。
 */
class SqliteAuraBoostConfigProvider(
    private val service: AuraBoostConfigService,
    private val cardGroupService: CardGroupService
) : AuraBoostConfigProvider {
    override fun findAll(): List<AuraBoostConfig> {
        val enabledManagerIds = cardGroupService.loadAllManagers()
            .filter { it.enabled }
            .mapTo(mutableSetOf()) { it.id }
        return service.loadAll()
            .filter { it.managerId == null || it.managerId in enabledManagerIds }
            // T-SR-012（open-questions Q-OQ-002）：启用开关——false 留库但不进引擎（临时停用通道）。
            // ⚠️ 行级：与"归属过滤"叠加后仍只表达"这条规则要不要"，不表达"对哪个卡组"（引擎无当前卡组概念）。
            .filter { it.enabled }
            .map { entity ->
                AuraBoostConfig(
                    id = entity.id,
                    name = entity.name,
                    conditionId = entity.conditionId,
                    targetConditionId = entity.targetConditionId,
                    score = entity.score
                )
            }
    }
}
