package lin.ui.card_purpose

import lin.bean.usePlan.PurposeTagId

/**
 * 用途标签目录提供者。
 *
 * 解耦标签定义来源，方便后续切换为数据库、SPI、配置文件等实现。
 * 消费者通过 Koin 注入该接口获取标签列表。
 */
interface PurposeTagProvider {
    fun tags(): List<PurposeTagDef>

    /** 按标签 id 查找显示名，未找到时降级返回原始 id */
    fun displayName(id: String): String =
        tags().find { it.id.value == id }?.displayName ?: id
}

/**
 * 默认标签提供者：硬编码的 6 个基础标签。
 *
 * 作为 [PurposeTagProvider] 的 fallback 实现；
 * 后续可替换为数据库驱动或其他外部配置加载实现。
 */
class DefaultPurposeTagProvider : PurposeTagProvider {
    override fun tags(): List<PurposeTagDef> = listOf(
        PurposeTagDef(PurposeTagId.SAVE_LIFE, "保命"),
        PurposeTagDef(PurposeTagId.CLEAN, "解场/清场"),
        PurposeTagDef(PurposeTagId.GREED, "成长/贪婪"),
        // Q-033 收口：去掉原「收尾」措辞——FINISH 已不承担编排映射（无 END 阶段映射），
        // 「收尾」暗示时序会误导。现仅作查询标签：「这张牌能用于斩杀」，何时斩杀由评估树判定。
        PurposeTagDef(PurposeTagId.FINISH, "斩杀"),
        PurposeTagDef(PurposeTagId.VALUE, "普通价值"),
        PurposeTagDef(PurposeTagId.EXTRA_COST, "额外费用"),
        PurposeTagDef(PurposeTagId.DRAW_CARD, "过牌")
    )
}
