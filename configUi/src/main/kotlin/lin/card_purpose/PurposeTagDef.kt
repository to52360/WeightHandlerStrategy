package lin.card_purpose

import lin.bean.usePlan.PurposeTagId

/**
 * 用途标签定义。
 *
 * @param id 标签标识（对应引擎层 [PurposeTagId] 常量）
 * @param displayName UI 层使用的显示名
 */
data class PurposeTagDef(
    val id: PurposeTagId,
    val displayName: String
)
