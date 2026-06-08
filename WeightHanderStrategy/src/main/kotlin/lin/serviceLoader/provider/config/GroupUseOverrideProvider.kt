package lin.serviceLoader.provider.config

import lin.bean.usePlan.GroupUseOverride

/**
 * 分组使用覆盖提供者。
 *
 * @deprecated 行为属性已合并到 CardGroupBinding，由 CardGroupIndexProvider.provideBindingOverrides() 提供。
 * 保留此接口仅为 SPI 兼容，新代码不应使用。
 */
@Deprecated("行为属性已合并到 CardGroupBinding，使用 CardGroupIndexProvider.provideBindingOverrides() 替代")
fun interface GroupUseOverrideProvider {
    fun findAllEnabled(): Map<String, GroupUseOverride>
}
