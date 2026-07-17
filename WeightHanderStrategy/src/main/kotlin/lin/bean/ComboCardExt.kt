package lin.bean

import lin.bean.usePlan.PurposeTagId

/** 该卡配置的所有用途标签值列表（String），无配置时返回空列表。 */
fun ComboCard.purposeTagValues(): List<String> =
    combinedConfig?.purposeTags?.map(PurposeTagId::value) ?: emptyList()
