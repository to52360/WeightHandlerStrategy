package lin.domain.use.order

import lin.domain.use.plan.UsePlanOrderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsePlanOrdererTest {

    @Test
    fun testStableSortNoConstraints() {
        val baseOrdered = listOf("Prep", "Minion", "Eviscerate")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = emptyList(),
            togetherConstraints = emptyList()
        )
        assertEquals(baseOrdered, result)
    }

    @Test
    fun testBeforeConstraints() {
        val baseOrdered = listOf("Minion", "Prep", "Eviscerate")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = listOf("Prep" to "Eviscerate"),
            togetherConstraints = emptyList()
        )
        assertEquals(listOf("Minion", "Prep", "Eviscerate"), result)

        val baseOrdered2 = listOf("Eviscerate", "Prep")
        val result2 = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered2,
            beforeConstraints = listOf("Prep" to "Eviscerate")
        )
        assertEquals(listOf("Prep", "Eviscerate"), result2)
    }

    @Test
    fun testTogetherConstraintsAdjacency() {
        val baseOrdered = listOf("Minion", "Prep", "Spell")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = emptyList(),
            togetherConstraints = listOf("Prep" to "Spell")
        )
        assertEquals(listOf("Minion", "Prep", "Spell"), result)

        // 压力测试场景：虽然 Minion 在 baseOrdered 中排在 Spell 之前，
        // 但由于 Prep 与 Spell 强邻接，弹出 Prep 时必须立刻带出 Spell，Minion 靠后！
        val baseOrdered2 = listOf("Prep", "Minion", "Spell")
        val result2 = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered2,
            beforeConstraints = emptyList(),
            togetherConstraints = listOf("Prep" to "Spell")
        )
        assertEquals(listOf("Prep", "Spell", "Minion"), result2)
    }

    @Test
    fun testCyclicDependencyFallback() {
        val baseOrdered = listOf("A", "B")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = listOf("A" to "B", "B" to "A")
        )
        assertNull(result)
    }

    @Test
    fun testTogetherCyclicOrConflicting() {
        val baseOrdered = listOf("A", "B")
        val result = UsePlanOrderer.stableSortWithConstraints(
            baseOrdered = baseOrdered,
            beforeConstraints = emptyList(),
            togetherConstraints = listOf("A" to "B", "B" to "A")
        )
        assertNull(result)
    }
}
