package lin.ui.components.form

import org.junit.Assert.*
import org.junit.Test

/**
 * T-DC-010：文本字段约束纯逻辑单测（headless 可跑，不触碰 JavaFX 控件）。
 * 覆盖各原子约束、组合子与聚合校验 [evaluateField] 的求值语义。
 */
class FieldConstraintTest {

    @Test
    fun `Required 空白或全空格返回文案`() {
        val c = FieldConstraint.Required("不能为空")
        assertEquals("不能为空", c.evaluate(""))
        assertEquals("不能为空", c.evaluate("   "))
    }

    @Test
    fun `Required 非空白通过`() {
        assertNull(FieldConstraint.Required("不能为空").evaluate("AuraBoost"))
    }

    @Test
    fun `Decimal 有效数字通过 非数字与非法格式返回文案`() {
        val c = FieldConstraint.Decimal("请输入有效的费值（如 1.2）")
        assertNull(c.evaluate("1.5"))
        assertNull(c.evaluate("0"))
        assertNull(c.evaluate("-2.25"))
        // 空值不属于 Decimal 的职责（由 Required 负责）
        assertNull(c.evaluate(""))
        assertEquals("请输入有效的费值（如 1.2）", c.evaluate("abc"))
        assertEquals("请输入有效的费值（如 1.2）", c.evaluate("1.2.3"))
    }

    @Test
    fun `MaxLength 超长返回文案 边界长度通过`() {
        val c = FieldConstraint.MaxLength(60, "最多 60 字符")
        assertNull(c.evaluate("x".repeat(60)))
        assertEquals("最多 60 字符", c.evaluate("x".repeat(61)))
    }

    @Test
    fun `All 任一失败即返回首个失败文案`() {
        val c = FieldConstraint.All(
            listOf(
                FieldConstraint.Required("不能为空"),
                FieldConstraint.MaxLength(60, "最多 60 字符")
            )
        )
        assertEquals("不能为空", c.evaluate(""))
        assertEquals("最多 60 字符", c.evaluate("x".repeat(61)))
        assertNull(c.evaluate("合法名称"))
    }

    @Test
    fun `Any 任一通过即通过 全失败返回首个失败文案`() {
        val c = FieldConstraint.Any(
            listOf(
                FieldConstraint.Decimal("必须为数字"),
                FieldConstraint.MaxLength(5, "最多 5 字符")
            )
        )
        assertNull(c.evaluate("1.5"))
        assertNull(c.evaluate("abc"))
        assertEquals("必须为数字", c.evaluate("abcdefgh"))
    }

    @Test
    fun `Not 内层失败则通过 内层通过则返回文案`() {
        val c = FieldConstraint.Not(FieldConstraint.Required("不能为空"), "不得填写")
        assertNull(c.evaluate(""))
        assertEquals("不得填写", c.evaluate("abc"))
    }

    @Test
    fun `evaluateField 聚合产出 FieldProblem`() {
        val problems = evaluateField(
            fieldId = "name",
            label = "名称",
            constraints = listOf(
                FieldConstraint.Required("不能为空"),
                FieldConstraint.MaxLength(60, "最多 60 字符")
            ),
            raw = "   "
        )
        assertEquals(1, problems.size)
        assertEquals("name", problems[0].fieldId)
        assertEquals("名称", problems[0].label)
        assertEquals("不能为空", problems[0].message)
    }

    @Test
    fun `evaluateField 全部通过则无问题 无约束则恒空`() {
        assertTrue(
            evaluateField(
                fieldId = "score",
                label = "加分费值",
                constraints = listOf(FieldConstraint.Decimal("请输入有效的费值（如 1.2）")),
                raw = "1.5"
            ).isEmpty()
        )
        assertTrue(
            evaluateField(fieldId = "desc", label = "描述", constraints = emptyList(), raw = "任意文本").isEmpty()
        )
    }
}
