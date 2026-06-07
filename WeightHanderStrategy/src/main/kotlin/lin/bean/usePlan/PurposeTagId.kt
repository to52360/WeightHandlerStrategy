package lin.bean.usePlan

/**
 * 用途标签值对象。
 *
 * 以字符串 id 承载标签语义，替代原有封闭枚举。
 * 默认标签作为 companion object 常量保留，不构成封闭集合。
 *
 * 中文显示名由 configUi 层的 [PurposeTagProvider] 提供，
 * 引擎层不持有显示相关逻辑。
 */
@JvmInline
value class PurposeTagId(val value: String) {
    companion object {
        /** 保命牌：低血量或防御回合决策 */
        val SAVE_LIFE = PurposeTagId("SAVE_LIFE")

        /** 解场/清场牌 */
        val CLEAN = PurposeTagId("CLEAN")

        /** 贪婪/成长/蓄力牌 */
        val GREED = PurposeTagId("GREED")

        /** 斩杀/收尾牌 */
        val FINISH = PurposeTagId("FINISH")

        /** 普通价值牌 */
        val VALUE = PurposeTagId("VALUE")

        /** 额外费用牌 */
        val EXTRA_COST = PurposeTagId("EXTRA_COST")
    }
}
