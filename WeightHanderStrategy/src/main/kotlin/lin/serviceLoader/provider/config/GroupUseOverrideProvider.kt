package lin.serviceLoader.provider.config

import lin.bean.usePlan.GroupUseOverride

/**
 * 分组使用覆盖提供者。
 *
 * SPI 入口，configUi 通过此接口读取 group_use_override 表。
 * Provider 自主决定数据范围。
 * 引擎端提供默认实现返回空 Map。
 */
fun interface GroupUseOverrideProvider {
    fun findAllEnabled(): Map<String, GroupUseOverride>
}
