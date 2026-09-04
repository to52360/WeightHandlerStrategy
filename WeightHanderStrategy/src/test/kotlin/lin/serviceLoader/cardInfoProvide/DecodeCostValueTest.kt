package lin.serviceLoader.cardInfoProvide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D-007 小数位编码 v4 编解码（2026-09-04，sop-rework T-002/C）：
 * 等效费放宽到 0.1 精度（3.5 = 等效 3.5 费，不再被误读为 等效3/门槛5）；门槛 N 编码在百分位
 * （3.54 = 等效 3.5 费 / 垫后余量 4，D-012）。整数/一位小数 = 未配置门槛（N=0 付得起即垫）。
 */
class DecodeCostValueTest {

    @Test
    fun `整等效费配门槛编码在百分位`() {
        val decoded = decodeCostValue(5.04)
        assertEquals(5.0, decoded.equivalentCostValue, 1e-9)
        assertEquals(4, decoded.surplusIdleThreshold)
    }

    @Test
    fun `一位小数等效费不再被误读为门槛`() {
        // v3 反直觉坑修复：3.5 现为等效 3.5 费，非 等效3/门槛5
        val decoded = decodeCostValue(3.5)
        assertEquals(3.5, decoded.equivalentCostValue, 1e-9)
        assertNull(decoded.surplusIdleThreshold)
    }

    @Test
    fun `半费等效费配门槛`() {
        val decoded = decodeCostValue(3.54)
        assertEquals(3.5, decoded.equivalentCostValue, 1e-9)
        assertEquals(4, decoded.surplusIdleThreshold)
    }

    @Test
    fun `整数表示未配置门槛`() {
        val decoded = decodeCostValue(5.0)
        assertEquals(5.0, decoded.equivalentCostValue, 1e-9)
        assertNull(decoded.surplusIdleThreshold)
    }

    @Test
    fun `v3 存量一位小数语义翻转为纯等效费`() {
        // 迁移债：v3 的 5.4（等效5费/门槛4）在 v4 读作等效 5.4 费、无门槛；需改写为 5.04 才保留门槛。
        val decoded = decodeCostValue(5.4)
        assertEquals(5.4, decoded.equivalentCostValue, 1e-9)
        assertNull(decoded.surplusIdleThreshold)
    }

    @Test
    fun `浮点误差不误判门槛`() {
        // 5.04 二进制不精确，按"分"归一后仍稳定解出 门槛 4
        val decoded = decodeCostValue(5.0 + 0.04)
        assertEquals(5.0, decoded.equivalentCostValue, 1e-9)
        assertEquals(4, decoded.surplusIdleThreshold)
    }

    @Test
    fun `手误多小数位被百分位截断`() {
        val decoded = decodeCostValue(5.99)
        assertEquals(5.9, decoded.equivalentCostValue, 1e-9)
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
        // 0.01 = 等效 0 / 门槛 1（门槛 1 = 随时可垫，等价未配置）
        val decoded = decodeCostValue(0.01)
        assertEquals(0.0, decoded.equivalentCostValue, 1e-9)
        assertEquals(1, decoded.surplusIdleThreshold)
    }

    @Test
    fun `encode-decode 往返一致`() {
        assertEquals(5.04, encodeCostValue(5.0, 4), 1e-9)
        assertEquals(3.54, encodeCostValue(3.5, 4), 1e-9)
        assertEquals(3.5, encodeCostValue(3.5, null), 1e-9)
        assertEquals(5.0, encodeCostValue(5.0, null), 1e-9)
        assertEquals(0.01, encodeCostValue(0.0, 1), 1e-9)
        for (raw in listOf(5.04, 3.5, 3.54, 0.01, 9.99, 1.0, 0.0)) {
            val decoded = decodeCostValue(raw)
            assertEquals(raw, encodeCostValue(decoded.equivalentCostValue, decoded.surplusIdleThreshold), 1e-9)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode 拒绝非 0_1 精度等效费`() {
        encodeCostValue(3.54, null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode 拒绝越界门槛`() {
        encodeCostValue(5.0, 0)
    }
}
