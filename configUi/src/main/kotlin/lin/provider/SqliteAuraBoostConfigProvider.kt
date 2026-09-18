package lin.provider

import lin.bean.AuraBoostConfig
import lin.repository.aura_boost.AuraBoostConfigService
import lin.repository.card_group.AuraDelta
import lin.repository.card_group.CardGroupService
import lin.repository.card_group.CurrentDeckContext
import lin.repository.card_group.DimensionItemResolver
import lin.repository.card_group.DimensionScope
import lin.repository.card_group.StrategyPresetRepository
import lin.serviceLoader.provider.AuraBoostConfigProvider

/**
 * aura_boost 表的引擎侧 SPI 实现（aura-boost）。
 * 引擎 AuraBoostConfig 不含 managerId（归属在配置侧），映射时丢弃。
 *
 * **供给过滤（D-DP-001 / Q-DP-002，取代 2026-08-08 的「全局行对所有启用卡组隐式生效」）**：
 * - **全局行（`manager_id` = null）= 候选池** —— 只有被「引用预设声明的**白名单** ∪ 本卡组增量 **extra** − **exclude**」
 *   纳入才生效；**未引用预设 ⇒ 无全局光环**（与 D-TG-018「不引用预设 = 合法终态、无隐式作用」同向）。
 * - **卡组私有行（`manager_id` = 启用卡组）**：口径不动（归属即拥有，不受白名单约束）。
 * - 分值为 `增量项 scoreOverrides[id] ?: 原分值`（消费侧的覆盖通道）。
 * - 装配期一次性解析 ⇒ **改配置需重启引擎**（与切换预设同款约束）。
 */
class SqliteAuraBoostConfigProvider(
    private val service: AuraBoostConfigService,
    private val cardGroupService: CardGroupService,
    /** D-DP-001：预设白名单 + 卡组增量项（同一张 `strategy_dimension_item`）。 */
    private val presetRepository: StrategyPresetRepository,
    /** 当前卡组 + 其引用的预设（单点口径，与树 / 时序 provider 同源）。 */
    private val currentDeck: CurrentDeckContext,
    private val resolver: DimensionItemResolver
) : AuraBoostConfigProvider {

    override fun findAll(): List<AuraBoostConfig> {
        val enabledManagerIds = cardGroupService.loadAllManagers()
            .filter { it.enabled }
            .mapTo(mutableSetOf()) { it.id }

        val presetId = currentDeck.currentPresetId()
        val whitelist = presetId
            ?.let { presetRepository.findAuraSelection(DimensionScope.PRESET, it) }
            .orEmpty()
        val delta = currentDeck.current()
            ?.let { presetRepository.findAuraDelta(DimensionScope.CARD_GROUP, it.id) }
            ?: AuraDelta.NONE
        val effective = resolver.auraEffective(presetId != null, whitelist, delta)

        return service.loadAll()
            // T-SR-012（open-questions Q-OQ-002）：行级启用开关——false 留库不进引擎（临时停用通道）。
            .filter { it.enabled }
            .filter { entity ->
                if (entity.managerId != null) {
                    // 卡组私有：归属即拥有（口径不动）
                    entity.managerId in enabledManagerIds
                } else {
                    // 全局行：候选池 ⇒ 被白名单 / extra 纳入且未被 exclude
                    entity.id in effective
                }
            }
            .map { entity ->
                AuraBoostConfig(
                    id = entity.id,
                    name = entity.name,
                    conditionId = entity.conditionId,
                    targetConditionId = entity.targetConditionId,
                    score = delta.scoreOverrides[entity.id] ?: entity.score
                )
            }
    }
}
