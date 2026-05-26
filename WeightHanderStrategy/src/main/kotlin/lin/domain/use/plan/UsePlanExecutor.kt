package lin.domain.use.plan

fun interface UsePlanExecutor {
    /**
     * 执行已经完成选择和排序的 UsePlan。
     * 第一版不接真实打牌，后续再桥接现有 UseDomain。
     */
    fun execute(plan: UsePlan): Boolean
}

object TodoUsePlanExecutor : UsePlanExecutor {
    /**
     * 占位实现，避免当前骨架提前牵扯动画、发现同步和 reload 逻辑。
     */
    override fun execute(plan: UsePlan): Boolean {
        TODO("接入真实 UseDomain 执行流程")
    }
}
