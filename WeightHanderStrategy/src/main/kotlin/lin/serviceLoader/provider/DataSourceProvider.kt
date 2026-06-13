package lin.serviceLoader.provider

import lin.rule.condition.orthogonal.DataSource

/**
 * 动态数据源的 SPI 注册入口接口。
 */
interface DataSourceProvider {
    fun get(): Collection<DataSource<*>>
}
