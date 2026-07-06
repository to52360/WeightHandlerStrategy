package lin.ui.card_group.db

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.bean.usePlan.GroupUseOverride
import lin.rule.tree.CardGroupBinding

private val mapper = jacksonObjectMapper()

/** 对应 DB 表 card_group_manager 的行记录 */
data class CardManagerEntity(
    val id: String,       // UUID，主键
    val name: String,
    val sourceFile: String, // 新增：来源 .cardgroup 文件名
    val enabled: Boolean
)

/**
 * 对应 DB 表 card_group_binding 的行记录。
 * 使用 id 作为主键。
 * overrides 存储序列化后的 GroupUseOverride 属性。
 */
data class CardBindingEntity(
    val id: String,
    val managerId: String,
    val name: String,
    val cardIds: String, // JSON 数组字符串
    val overrides: String? = null, // JSON 字符串
    val description: String? = null
) {
    fun toDomain(): CardGroupBinding {
        return CardGroupBinding(
            id = id,
            managerId = managerId,
            name = name,
            cardIds = mapper.readValue(cardIds),
            overrides = overrides?.let { mapper.readValue<GroupUseOverride>(it) },
            description = description
        )
    }
}
