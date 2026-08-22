package lin.serviceLoader.cardInfoProvide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D-007 小数位编码 v2 解码（phase-1 存储侧约定）：
 * `powerWeight = 等效费 + 空闲放行门槛/10`（5.4 = 等效 5 费 / 空闲 ≥4 放行「3捏4放行」），整数 = 未配置（随时可垫）。
 */
class DecodeCostValueTest {

    @Test
    fun `编码值拆分为等效费与放行门槛`() {
        val decoded = decodeCostValue(5.4)
        assertEquals(5.0, decoded.equivalentCostValue, 1e-9)
        assertEquals(4, decoded.surplusIdleThreshold)
    }

    @Test
    fun `整数表示未配置门槛`() {
        val decoded = decodeCostValue(5.0)
        assertEquals(5.0, decoded.equivalentCostValue, 1e-9)
        assertNull(decoded.surplusIdleThreshold)
    }

    @Test
    fun `已有小数配置被重新解释为编码`() {
        // 迁移债约束：历史 3.5 不再是「等效3/门槛5」
        val decoded = decodeCostValue(3.5)
        assertEquals(3.0, decoded.equivalentCostValue, 1e-9)
        assertEquals(5, decoded.surplusIdleThreshold)
    }

    @Test
    fun `浮点误差不误判未配置`() {
        // 5.0 + 0.4 二进制表示带误差，fraction ≈ 0.40000000000000036，不得因 epsilon 内的偏差判为未配置
        val decoded = decodeCostValue(5.0 + 0.4)
        assertEquals(5.0, decoded.equivalentCostValue, 1e-9)
        assertEquals(4, decoded.surplusIdleThreshold)
    }

    @Test
    fun `手误双位小数钳到 9`() {
        val decoded = decodeCostValue(5.99)
        assertEquals(5.0, decoded.equivalentCostValue, 1e-9)
        assertEquals(9, decoded.surplusIdleThreshold)
    }

    @Test
    fun `零费无配置保持三分流哨兵语义`() {
        val decoded = decodeCostValue(0.0)
        assertEquals(0.0, decoded.equivalentCostValue, 1e-9)
        assertNull(decoded.surplusIdleThreshold)
    }

    @Test
    fun `等效费为 0 但配置了门槛`() {
        // 0.1 = 等效 0 / 门槛 1（门槛 1 = 随时可垫，等价未配置）
        val decoded = decodeCostValue(0.1)
        assertEquals(0.0, decoded.equivalentCostValue, 1e-9)
        assertEquals(1, decoded.surplusIdleThreshold)
    }
}
