package lin.ui

import lin.rule.build.DynamicFieldOption
import lin.serviceLoader.provider.SelectOptionProvider
import lin.utils.serviceLoader.ServiceLoaderUtils

/**
 * 动态下拉选项注册中心
 * 用于管理通过 dataSource 声明的下拉菜单数据源提供者，支持 SPI 自动装配
 */
class SelectOptionRegistry {
    // 基于 SPI (ServiceLoaderUtils) 懒加载的服务提供者
    private val spiProviders: Map<String, SelectOptionProvider> by lazy {
        ServiceLoaderUtils.loadServices(SelectOptionProvider::class.java)
            .associateBy { it.dataSourceId }
    }

    /**
     * 获取指定数据源的下拉选项
     */
    fun getOptions(dataSource: String): List<DynamicFieldOption> {
        return spiProviders[dataSource]?.getOptions() ?: emptyList()
    }
}
