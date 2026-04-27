package lin.serviceLoader.provider

import lin.rule.build.DynamicFieldOption

/**
 * SPI 扩展接口：外部插件或模块可通过实现此接口并配置 META-INF/services/lin.serviceLoader.provider.SelectOptionProvider 来自动注册下拉数据源
 */
interface SelectOptionProvider {
    // 数据源的唯一标识
    val dataSourceId: String

    // 返回对应的下拉选项
    fun getOptions(): List<DynamicFieldOption>


    /*   待定
     // 核心：判断是否合法
        fun contains(value: Any): Boolean

        // 可选：值 → 展示
        fun getLabel(value: Any): String?

        // UI 用：搜索
        fun query(keyword: String, limit: Int): List<DynamicFieldOption>*/
}