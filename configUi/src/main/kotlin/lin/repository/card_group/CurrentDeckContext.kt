package lin.repository.card_group

import lin.myLog

/**
 * 「**当前卡组**」的唯一解析入口（T-TG-015 单点化）。
 *
 * 引擎装配是**启动期一次性**（`CardConfigBindingTask`），且 `CardGroupService.saveManager` 在
 * `enabled = true` 时会自动禁用其余卡组（单活约束）⇒ 配置侧 `findManagers(onlyEnabled = true)`
 * 拿到的就是"当前卡组"，**不需要引擎识别当前卡组**（Q-TG-001 仍不需要）。
 *
 * ⚠️ 本类是**单点**：此前这个判断被抄了三份、且口径不一致 ——
 * `SqliteTreeConfigProvider.enabledGroupIds()` 用**全部** enabled 卡组，
 * 而 `excludedTreeIds()` / `StrategyPresetService.currentPresetId()` 用**第一个**；
 * 加消费方增量项后还需要"当前卡组 id"，故统一收敛到这里。
 */
class CurrentDeckContext(private val groupRepository: CardGroupRepository) {

    /**
     * 当前卡组；无 enabled 卡组时 `null`。
     *
     * 多 enabled 属**异常态**（单活约束被绕过）⇒ 告警并按 id 稳定取第一个
     * （此处是按 id 定序的**去歧义**手段，不是业务排序）。
     */
    fun current(): CardManagerEntity? {
        val enabled = groupRepository.findManagers(onlyEnabled = true)
        if (enabled.isEmpty()) return null
        if (enabled.size > 1) {
            myLog.warn {
                "存在多个 enabled 卡组（${enabled.map { it.id }}）—— 单活约束应只保留一个；" +
                        "按 id 稳定取第一个，其余卡组的分组/预设将不参与解析"
            }
        }
        return enabled.minByOrNull { it.id }
    }

    /** 当前卡组引用的预设 id（空白视为未引用）。 */
    fun currentPresetId(): String? = current()?.presetId?.takeIf { it.isNotBlank() }
}
