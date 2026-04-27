package lin.rule.registry

import lin.rule.build.DynamicFieldOption
import lin.serviceLoader.provider.SelectOptionProvider
import lin.utils.serviceLoader.ServiceLoaderUtils


/**
 * 动态下拉选项注册中心
 * 用于管理通过 dataSource 声明的下拉菜单数据源提供者，支持手动注册和 SPI 自动装配
 */
object SelectOptionRegistry {
    // 内存中手动注册的供应者
    private val memoryProviders = mutableMapOf<String, () -> List<DynamicFieldOption>>()

    // 基于 SPI (ServiceLoaderUtils) 懒加载的服务提供者
    private val spiProviders: Map<String, SelectOptionProvider> by lazy {
        ServiceLoaderUtils.loadServices(SelectOptionProvider::class.java)
            .associateBy { it.dataSourceId }
    }

    /**
     * 手动注册一个下拉数据源代码 (代码级别的注册)
     */
    fun register(dataSource: String, provider: () -> List<DynamicFieldOption>) {
        memoryProviders[dataSource] = provider
    }

    /**
     * 获取指定数据源的下拉选项
     * 优先级: 手动代码注册(Memory) > SPI扩展发现
     */
    fun getOptions(dataSource: String): List<DynamicFieldOption> {
        // 先检查是否有手动注册覆盖
        memoryProviders[dataSource]?.let { return it.invoke() }
        // 否则返回 SPI 找到的
        return spiProviders[dataSource]?.getOptions() ?: emptyList()
    }
}
