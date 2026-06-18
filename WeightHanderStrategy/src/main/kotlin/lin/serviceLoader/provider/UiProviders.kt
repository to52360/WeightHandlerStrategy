package lin.serviceLoader.provider

import lin.rule.build.DynamicFieldOption

/**
 * SPI 扩展接口：外部插件或模块可通过实现此接口以自动注册下拉数据源
 */
interface SelectOptionProvider {
    // 数据源的唯一标识
    val dataSourceId: String

    // 返回对应的下拉选项
    fun getOptions(): List<DynamicFieldOption>
}
