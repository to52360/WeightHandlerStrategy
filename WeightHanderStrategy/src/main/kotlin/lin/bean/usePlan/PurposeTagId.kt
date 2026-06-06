package lin.bean.usePlan

/**
 *
 * todo 这里还要额外封装对象,封装名字信息,还没有数据库支持
 * 用途标签值对象。
 *
 * 以字符串 id 承载标签语义，替代原有封闭 [PurposeTag] 枚举。
 * 默认标签作为 companion object 常量保留，不构成封闭集合。
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

        /** 所有默认用途标签常量集合 */
        val DEFAULTS = setOf(SAVE_LIFE, CLEAN, GREED, FINISH, VALUE, EXTRA_COST)
    }
}
