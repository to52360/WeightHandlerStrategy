package lin.ui

import lin.rule.tree.CardGroupBinding
import lin.rule.tree.GroupMembership
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-007：展示层「cardIds 失真」修复——谓词组必须显式区分成员类型，不能显示 0 张（空静态组误读）。
 */
class GroupDisplayTest {

    private fun binding(membership: GroupMembership, name: String = "法术组") =
        CardGroupBinding(id = "g1", managerId = "m1", name = name, membership = membership)

    @Test
    fun `静态组显示纯名称`() {
        assertEquals("法术组", GroupDisplay.displayNameWithType(binding(GroupMembership.Static(listOf("A")))))
    }

    @Test
    fun `谓词组加标记`() {
        assertEquals("法术组 [谓词组]", GroupDisplay.displayNameWithType(binding(GroupMembership.Predicate("t1"))))
    }

    @Test
    fun `静态组成员概要显示卡数`() {
        val b = binding(GroupMembership.Static(listOf("A", "B")))
        assertEquals("2 张", GroupDisplay.memberSummary(b))
    }

    @Test
    fun `谓词组不显示 0 张而是条件组`() {
        val b = binding(GroupMembership.Predicate("t1"))
        assertEquals("条件组", GroupDisplay.memberSummary(b))
    }
}
