package lin.serviceLoader.provider.config

import lin.rule.condition.ConditionTreeConfig

// 当前数据量下 findById 逐个查询（~30 次）≈ 1.5ms。
// 条件树是无启用概念的通用模板，数据量可能较大，不适合全量预加载。
// 后续若数据量增大，Provider 内部加 ConcurrentHashMap 缓存即可防御，consumer 代码无需修改。
interface ConditionTreeConfigProvider {
    fun findById(id: String): ConditionTreeConfig?
    fun findAll(): List<ConditionTreeConfig>
}
