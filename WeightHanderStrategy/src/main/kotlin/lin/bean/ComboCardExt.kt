package lin.bean

import lin.bean.usePlan.PurposeTagId

/** 该卡配置的所有用途标签值列表（String），无配置时返回空列表。 */
fun ComboCard.purposeTagValues(): List<String> =
    combinedConfig?.purposeTags?.map(PurposeTagId::value) ?: emptyList()

/** 该卡是否持有指定用途标签（含机制牌启动期硬编码注入的标签，见 [lin.serviceLoader.cardInfoProvide.COINProvide.mechanismPurposes]）。 */
fun ComboCard.hasPurposeTag(tag: PurposeTagId): Boolean =
    combinedConfig?.purposeTags?.contains(tag) == true
