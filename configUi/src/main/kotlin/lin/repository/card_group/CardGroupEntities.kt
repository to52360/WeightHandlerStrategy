package lin.repository.card_group

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.rule.tree.CardGroupBehavior
import lin.rule.tree.CardGroupBinding
import lin.rule.tree.GroupMembership

private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

/** 对应 DB 表 card_group_manager 的行记录 */
data class CardManagerEntity(
    val id: String,       // UUID，主键
    val name: String,
    val sourceFile: String, // 来源 .cardgroup 文件名
    val enabled: Boolean,
    val description: String? = null, // 卡组总体描述/规划（manager_description 列，2026-08-10）
    val status: String? = null,      // 配置进度状态（manager_status 列，2026-08-10）：PLANNED / IN_PROGRESS / CONFIGURED
    // 卡组级「谓词组是否纳入卡池外的卡」默认值（T-001）：null=未声明，组级未设时回落 false
    val defaultIncludeDerived: Boolean? = null,
    /** 卡组引用的用途预设（T-TG-012）：null = 不用预设（走全局用途规则）。 */
    val presetId: String? = null
)

/**
 * 成员资格类型常量，对应 card_group_binding.member_type 列（T-001）。
 * 权威定义放在持久层（本文件，同 [GroupBehaviorType]）；领域层只持有密封子类 [GroupMembership]。
 */
object MemberType {
    /** 静态组：显式卡列表，成员存 card_ids 列。 */
    const val STATIC = "STATIC"

    /** 谓词组：条件树定义成员，条件 id 存 condition_id 列，运行期求值；card_ids 存空数组。 */
    const val PREDICATE = "PREDICATE"
}

/**
 * 对应 DB 表 card_group_binding 的行记录。
 * 使用 id 作为主键。行为覆盖（OVERRIDE）与使用动作（USE_ACTION）已迁到
 * 独立的 card_group_behavior 表，本表只管"分组含哪些卡"。
 *
 * 成员资格有两种形态（T-001，领域层为 [GroupMembership] 密封类）：
 * - `STATIC`：显式卡列表，存 [cardIds]。
 * - `PREDICATE`：条件树定义成员，存 [conditionId]，运行期求值，[cardIds] 存空数组。
 *
 * [cardIds] 列对所有类型都保留（后续 SingleCard 等类型可复用该列，不再加列）。
 * [conditionId] / [includeDerived] 仅 PREDICATE 有意义，列级可空——
 * 领域层由密封类封装后，下游访问点无 null。
 */
data class CardBindingEntity(
    val id: String,
    val managerId: String,
    val name: String,
    val cardIds: String, // JSON 数组字符串
    val description: String? = null,
    val memberType: String = MemberType.STATIC, // 成员资格类型：STATIC / PREDICATE
    val conditionId: String? = null, // PREDICATE 专用：条件树 id
    val includeDerived: Boolean? = null // PREDICATE 专用：是否纳入卡池外的卡，null=回落卡组级默认
) {
    fun toDomain(
        behaviors: List<CardGroupBehavior> = emptyList()
    ): CardGroupBinding {
        return CardGroupBinding(
            id = id,
            managerId = managerId,
            name = name,
            membership = toMembership(),
            behaviors = behaviors,
            description = description
        )
    }

    private fun toMembership(): GroupMembership = when (memberType) {
        MemberType.PREDICATE -> {
            val cid = conditionId
            if (cid.isNullOrBlank()) {
                // 数据损坏保护：声明 PREDICATE 却无条件树 → 退化为静态组，避免整卡组加载失败
                GroupMembership.Static(mapper.readValue(cardIds))
            } else {
                GroupMembership.Predicate(conditionId = cid, includeDerived = includeDerived)
            }
        }

        else -> GroupMembership.Static(mapper.readValue(cardIds))
    }

    companion object {
        /**
         * 由领域对象构造 Entity（落库用）。成员资格 → 各列的映射集中在此，
         * 避免序列化逻辑散落到多处调用点。
         */
        fun fromDomain(binding: CardGroupBinding): CardBindingEntity = when (val m = binding.membership) {
            is GroupMembership.Static -> CardBindingEntity(
                id = binding.id,
                managerId = binding.managerId,
                name = binding.name,
                cardIds = mapper.writeValueAsString(m.cardIds),
                description = binding.description,
                memberType = MemberType.STATIC
            )

            is GroupMembership.Predicate -> CardBindingEntity(
                id = binding.id,
                managerId = binding.managerId,
                name = binding.name,
                // 谓词组没有静态成员清单，存空数组保持列 NOT NULL
                cardIds = "[]",
                description = binding.description,
                memberType = MemberType.PREDICATE,
                conditionId = m.conditionId,
                includeDerived = m.includeDerived
            )
        }
    }
}

/**
 * 分组行为类型常量，对应 card_group_behavior.behavior_type 列。
 * 权威定义放在持久层（本文件）；领域层只持有密封子类 [CardGroupBehavior]，不再定义 DB 列值常量。
 */
object GroupBehaviorType {
    const val USE_ACTION = "USE_ACTION"
    const val OVERRIDE = "OVERRIDE"
    const val SURPLUS_GATE = "SURPLUS_GATE"
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
