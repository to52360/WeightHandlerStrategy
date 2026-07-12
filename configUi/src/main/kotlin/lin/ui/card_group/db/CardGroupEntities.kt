package lin.ui.card_group.db

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.bean.usePlan.GroupUseOverride
import lin.rule.tree.CardGroupBehavior
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
 * 使用 id 作为主键。行为覆盖（OVERRIDE）与使用动作（USE_ACTION）已迁到
 * 独立的 card_group_behavior 表，本表只管"分组含哪些卡"。
 */
data class CardBindingEntity(
    val id: String,
    val managerId: String,
    val name: String,
    val cardIds: String, // JSON 数组字符串
    val description: String? = null
) {
    fun toDomain(
        behaviors: List<CardGroupBehavior> = emptyList()
    ): CardGroupBinding {
        return CardGroupBinding(
            id = id,
            managerId = managerId,
            name = name,
            cardIds = mapper.readValue(cardIds),
            behaviors = behaviors,
            description = description
        )
    }
}

/**
 * 分组行为类型常量，对应 card_group_behavior.behavior_type 列。
 * 权威定义放在持久层（本文件）；领域层只持有密封子类 [lin.rule.tree.CardGroupBehavior]，不再定义 DB 列值常量。
 */
object GroupBehaviorType {
    const val USE_ACTION = "USE_ACTION"
    const val OVERRIDE = "OVERRIDE"
}

/**
 * 对应 DB 表 card_group_behavior 的行记录。
 * 通用「分组行为关联表」：每行一种分组级行为，(binding_id, behavior_type) 为主键。
 * payload 为 JSON，按 behavior_type 反序列化为对应数据类。
 * 新增行为类型只加行、不改主表 card_group_binding 结构。
 */
data class CardGroupBehaviorEntity(
    val bindingId: String,
    val behaviorType: String,
    val payload: String
)
