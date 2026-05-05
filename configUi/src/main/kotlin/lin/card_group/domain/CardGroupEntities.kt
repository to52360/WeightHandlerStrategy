package lin.card_group.domain

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
 */
data class CardBindingEntity(
    val id: String,
    val mangerId: String,
    val name: String,
    val cardIds: String // JSON 数组字符串
) {
    fun toDomain(): CardGroupBinding {
        return CardGroupBinding(
            id = id,
            mangerId = mangerId,
            name = name,
            cardIds = mapper.readValue(cardIds)
        )
    }
}
