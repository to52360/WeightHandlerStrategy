package condition

import lin.rule.parse.FieldConstraint
import lin.rule.parse.FieldSpec
import lin.rule.parse.FieldType
import lin.rule.parse.SpecValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpecValidatorTest {

    @Test
    fun testMissingRequiredField() {
        // 构造一个包含 Required 约束的字段元数据规范
        val specs = listOf(
            FieldSpec(
                propertyName = "limit",
                name = "限制值",
                description = "数值上限",
                typeStruct = FieldType.IntType,
                constraints = listOf(FieldConstraint.Required)
            )
        )

        // 校验一个不包含该必填字段的空参数字典
        val emptyArgs = emptyMap<String, Any?>()
        val result = SpecValidator.validate(emptyArgs, specs)

        // 断言：验证不通过，且包含 MISSING_REQUIRED 错误信息
        assertFalse(result.isValid, "应当校验失败，因为缺失必填参数")
        assertEquals(1, result.errors.size)

        val error = result.errors.first()
        assertEquals("limit", error.propertyName)
        assertEquals("MISSING_REQUIRED", error.errorCode)
        assertTrue(error.message.contains("缺失或为空"))
    }
}
