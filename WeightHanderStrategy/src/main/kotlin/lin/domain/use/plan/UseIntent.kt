package lin.domain.use.plan

data class UseIntent(
    val stage: UseStage = UseStage.VALUE,
    val tags: Set<UseTag> = emptySet(),
    val orderWeight: Double = 0.0
)

fun interface UseIntentProvider {
    /**
     * 根据卡牌所属的 CardGroupBinding.id 集合，生成新编排体系里的使用意图。
     * 第一版只声明边界，具体来源可以是 UI 配置、规则输出或手写配置。
     */
    fun intentOf(cardGroupIds: Set<String>): UseIntent
}

object TodoUseIntentProvider : UseIntentProvider {
    /**
     * 占位实现，防止新编排骨架提前绑定 DB、Koin 或旧 useGroupId 体系。
     */
    override fun intentOf(cardGroupIds: Set<String>): UseIntent {
        TODO("从 CardGroupBinding.id 或规则配置生成 UseIntent")
    }
}
