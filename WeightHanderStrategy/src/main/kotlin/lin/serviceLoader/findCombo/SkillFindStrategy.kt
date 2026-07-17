package lin.serviceLoader.findCombo


import lin.bean.ComboCard
import lin.bean.addSafe
import lin.domain.MyWarManage
import lin.domain.context.UseSkillWeight
import lin.domain.result.*
import lin.domain.strategy.FindComboStrategy.Companion.SKILL_PRIORITY
import lin.domain.strategy.FindPlanner
import lin.domain.use.*
import lin.myLog
import lin.warExt.my.base.getCost
import lin.warExt.my.base.getPower


class SkillFindStrategy : AbsFindStrategy(findRule = { false }), UseAfterStrategy {
    private val registerId = this.javaClass.simpleName
    private var skillComboCard: ComboCard? = null
    private var isUsedSkill = false

    //负权重不参与计算
    private var isNotCalculate = true

    /**
     * 没回合更新一次技能信息
     * @return 是否使用技能
     */
    fun processSkill(warManage: MyWarManage): Boolean {
        fun createSkill() {
            skillComboCard = warManage.getPower()?.let {
                val skill = warManage.parseComboCard(it)
                skill.addWeight(UseSkillWeight)
                extCost = -skill.cost()
                extWeight = skill.powerWeight
                skill.useAfterStrategy = skill.useAfterStrategy.addSafe(this)
                isNotCalculate = !skill.canUse()
                skill
            } ?: run { throw IllegalArgumentException("没有英雄技能") }
        }
        if (warManage.roundExecuteOnce(registerId)) {
            return isUsedSkill
        }
        skillComboCard?.let {
            val skill = warManage.getPower()
            if (it.card != skill) {
                myLog.info { "变更技能" }
                createSkill()
            }
        } ?: run {
            createSkill()
        }
        isUsedSkill = false
        return false



    }

    override fun priority() = SKILL_PRIORITY


    override fun isExecute(findPlanner: FindPlanner): Boolean {
        val warManage = findPlanner.warManage
        return processSkill(warManage) || skillComboCard?.let { warManage.getCost() < it.cost() } ?: true
    }
    fun useSkill(warManage: MyWarManage) {
        skillComboCard?.let {
            if (warManage.getCost() < it.cost()) return
            if (isUsedSkill) return
            val result = useCard(it)
            //todo-future 当前回合变更技能 临时处理方案
            if (!result) {
                val skill = warManage.getPower()
                if (it.card != skill) {
                    skill?.action?.power() ?: run {
                        myLog.warn { "没有技能信息" }
                    }
                }

            }
        } ?: run {
            myLog.warn { "没有技能信息" }
            return
        }

    }

    override fun emptyResultAction(findPlanner: FindPlanner) {
        isUsedSkill = true
        skillComboCard?.let {
            findPlanner.warManage.tryUseCard(it)
        } ?: throw IllegalStateException("没有技能信息")

    }

    override fun find(findPlanner: FindPlanner): CmdPlanner {
        if (isExecute(findPlanner)) return ContinuePlanner
        emptyResultAction(findPlanner) //直接使用技能
        return EmptyWeightResult.toPlanner()
    }

    override fun find(findPlanner: FindPlanner, weightResult: WeightResult): WeightResult {
        if (isExecute(findPlanner)) return weightResult
        if (isNotCalculate) {
            processIsNotCalculate(findPlanner, weightResult)
            return weightResult
        } else {
            return processResult(findPlanner, weightResult)
        }
    }

    private fun processIsNotCalculate(findPlanner: FindPlanner, weightResult: WeightResult) {
        when (weightResult) {
            is EndWeightResult -> skillComboCard?.let { weightResult.addUseCard(it) }
                ?: throw IllegalStateException("没有技能信息")

            is EmptyWeightResult -> emptyResultAction(findPlanner)
        }
    }

    override fun resultAction(findPlanner: FindPlanner, weightResult: WeightResult) {
        processIsNotCalculate(findPlanner, weightResult)
    }

    override fun afterExtAction(
        context: UseContext,
        useDomain: UseDomain
    ) {
        isUsedSkill = true
    }
}
