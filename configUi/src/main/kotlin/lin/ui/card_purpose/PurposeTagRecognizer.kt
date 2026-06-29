package lin.ui.card_purpose

import lin.bean.usePlan.PurposeTagId

/**
 * 基于卡牌元数据（cardId / 名称 / 类型等）智能推导 [PurposeTagId]。
 *
 * 当前为占位实现，后续评估后接入真实识别逻辑。
 * 识别失败应静默降级返回空集合，不抛异常。
 */
object PurposeTagRecognizer {

    /**
     * 根据 cardId 推导用途标签集合。
     *
     * TODO: 后续评估实现方案：
     * - 基于 cardId 前缀/模式匹配（如 "CS2_", "GAME_", "HERO_" 等）
     * - 基于卡牌类型 [lin.bean.CardWeightInfo.cardTypes]
     * - 基于卡牌名称关键词匹配
     * - 可配置规则表 vs 硬编码
     *
     * @return 推导出的标签集合，无法识别时返回空集合
     */
    fun recognize(cardId: String): Set<PurposeTagId> = emptySet()
}
