package lin.serviceLoader.findCombo


import lin.bean.ComboCard
import lin.bean.addSafe
import lin.domain.MyWarManage
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

    /**
     * 没回合更新一次技能信息
     * @return 是否使用技能
     */
    fun processSkill(warManage: MyWarManage): Boolean {
        fun createSkill() {
            skillComboCard = warManage.getPower()?.let {
                val skill = warManage.parseComboCard(it)
                skill.useAfterStrategy = skill.useAfterStrategy.addSafe(this)
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

    /**
     * D-007/T-016/T-020：技能 = 池外余费将就牌，`UseSkillWeight=-7` 负权重 hack 退役。
     *
     * T-016：旧 hack 机制（-7 → canUse=false → isNotCalculate 挡在组合外，仅空结果强制打出）与被其压死的
     * 「配置技能竞争路径」（copyResult 组合替换，需等效费≥9 才可能获胜的死代码）一并移除。
     *
     * T-020：技能以池外晚到候选身份参与**填充层竞争**——先单卡评估（评估树/Banned/负分/tacticalScore 生效，
     * cleanWeight 防跨轮重复累计），再 [retryFillWithLateCandidate] 与垫牌同池按 fillValue 竞争：
     * 高配技能可凭更高 fillValue 抢垫牌的费用（「抢真牌费用」场景），低配/负分/被 Banned 技能自然落选；
     * 主组合牌位不越级争（第一轮赢来的位置只属池内牌）。无牌可打仍由 [emptyResultAction] 兜底直接使用。
     */
    override fun find(findPlanner: FindPlanner, weightResult: WeightResult): WeightResult {
        if (isExecute(findPlanner)) return weightResult
        when (weightResult) {
            is EndWeightResult -> {
                val skill = skillComboCard ?: throw IllegalStateException("没有技能信息")
                skill.cleanWeight()
                findPlanner.weightHandlerDomain.evaluateStandaloneCard(skill)
                weightResult.retryFillWithLateCandidate(skill, findPlanner.warManage.isFull)
            }

            is EmptyWeightResult -> emptyResultAction(findPlanner)
        }
        return weightResult
    }

    override fun afterExtAction(
        context: UseContext,
        useDomain: UseDomain
    ) {
        isUsedSkill = true
    }
}
