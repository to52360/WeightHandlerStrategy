package lin.weightHandler.condition.config

import lin.weightHandler.condition.bean.ConditionGroup
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet

class GroupStrategyDao(private val jdbcTemplate: JdbcTemplate) {

    fun getAll(): List<ConditionGroup> {
        return jdbcTemplate.query("SELECT * FROM weight_group") { rs, _ ->
            ConditionGroup(
                groupId = rs.getInt("groupId"),
                bindId = rs.toDouble("bindId"),
                ruleId = rs.getString("weightConditionId"),
                depByWeightIds = rs.toDouble("depByWeightIds"),
                weight = rs.getDoubleOrNull("weight"),
                unConditionWeight = rs.getDoubleOrNull("unConditionWeight"),
                num = rs.getInt("num"),
            )
        }
    }


}

fun ResultSet.toDouble(columnName: String): Array<Double> {
    val value = getString(columnName)
    // 处理null值情况，返回空数组
    if (value.isNullOrEmpty()) return emptyArray()

    // 检查是否包含逗号分隔符
    return if (value.contains(',')) {
        // 按逗号分割并转换为Double数组
        value.split(',').map { it.toDouble() }.toTypedArray()
    } else {
        // 不包含逗号，视为单个值
        arrayOf(value.toDouble())
    }
}

fun ResultSet.getDoubleOrNull(columnName: String): Double? {
    val value = getObject(columnName)
    return when (value) {
        null -> null
        else -> anyToDouble(value)
    }
}

fun ResultSet.getDouble(columnName: String): Double {
    val value = getObject(columnName)
    return anyToDouble(value)
}

private fun anyToDouble(value: Any): Double {
    return when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Int -> value.toDouble()
        is Long -> value.toDouble()
        else -> throw IllegalArgumentException("Unsupported type for Double conversion: ${value.javaClass}")
    }
}