package lin.serviceLoader.provider

import lin.rule.orthogonal.DataSource
import lin.rule.orthogonal.Operator
import lin.rule.orthogonal.Transform
import lin.rule.score.ScoreOperator

/**
 * 动态数据源的 SPI 注册入口接口。
 */
interface DataSourceProvider {
    fun get(): Collection<DataSource<*>>
}

/**
 * 管道转换器（Transform）的 SPI 注册入口接口。
 */
interface TransformProvider {
    fun get(): Collection<Transform<*, *>>
}

/**
 * 动态算子的 SPI 注册入口接口。
 */
interface OperatorProvider {
    fun get(): Collection<Operator<*, *>>
}

/**
 * 评分算子的 SPI 注册入口接口。
 */
interface ScoreOperatorProvider {
    fun getScoreOperators(): Collection<ScoreOperator<*, *>>
}
