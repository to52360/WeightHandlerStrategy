package lin.rule.condition.orthogonal.spi

import lin.rule.condition.orthogonal.Operator

/**
 * 动态算子的 SPI 注册入口接口。
 */
interface OperatorProvider {
    fun get(): Collection<Operator<*, *>>
}
