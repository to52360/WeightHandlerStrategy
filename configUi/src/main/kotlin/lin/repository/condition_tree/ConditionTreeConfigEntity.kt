package lin.repository.condition_tree

/**
 * 条件树持久化实体。
 *
 * - [managerId]：归属卡组。null/空 = 全局共享树；非 null = 卡组私有树。
 *   按卡组过滤（LIST / 背景知识）返回"当前卡组私有 + 全局共享"两层的树。
 * - [inlineCreated]：标记由消费方（save_aura_boost / save_card_group）内联自动创建的"一次性树"，
 *   不参与评估树背景知识（list_capability_background）展示，避免评估树编排时被噪音干扰。
 */
data class ConditionTreeConfigEntity(
    val id: String,
    val name: String,
    val configData: String,
    val managerId: String? = null,
    val inlineCreated: Boolean = false
)
