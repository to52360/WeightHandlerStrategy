package lin.domain


import club.xiaojiawei.hsscriptbasestrategy.util.DeckStrategyUtil
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import lin.bean.ComboCard
import lin.bean.addSafe
import lin.bean.cardExt.base.isMinion
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import lin.domain.use.UseAfterStrategy
import lin.domain.use.UseContext
import lin.domain.use.UseDomain
import lin.domain.use.tryUseCard
import lin.lifecycle.LifecycleRegister
import lin.lifecycle.LifecycleRegisterImpl
import lin.myLog
import lin.rule.context.RuleEnv
import lin.rule.context.WarInfoEnv
import lin.serviceLoader.weightRule.utils.war.WarStatus
import lin.utils.database.dao.CardInfoDao
import lin.warExt.action.activeLocation
import lin.warExt.action.cleanPlay
import lin.warExt.action.cleanPlayAll
import lin.warExt.my.base.*
import lin.weightHandler.calcBaseValue
import lin.weightHandler.warHandler.ToDieHandler
import org.koin.core.component.KoinComponent
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap


/**
 * 还包含战场管理
 */
interface WarInfo {
    val war: War

    //手牌
    val handComboCards: List<ComboCard>

    //能够使用的手牌
    val canUseCards: List<ComboCard>

    //我方战场区域的牌
    val playComboCards: List<ComboCard>

    //权重信息
    val infoMap: Map<String, CardCombinedConfig>

    val extCost: Int

    //战场状态,判断局势用的
    val warStatus: WarStatus

    /**
     * 用于重复调用,但是每回合只能调用一次
     * @return 为true就执行过了
     */
    fun cleanPlayByRoundOnce(): Boolean

    /**
     * 没回合执行一次
     * @return 为ture表示执行过了
     */
    fun roundExecuteOnce(registryId: String): Boolean

    /**
     * 刷新战场信息
     */
    fun reloadPlayComboCards()

    /**
     * 回合生命周期处理
     * 问题只能处理一种类型
     */
    fun registerLifecycle(lifecycle: Any)

    /**
     * todo-future 会存在并发修改错误,谨慎使用
     */
    fun logoutLifecycle(lifecycle: Any)
}


/**
 * todo-future 东西太多,功能也太多了,看后面需不需要部分功能,采用组合
 * todo-future 有空的时候采用委托处理一下
 * select 没有使用私有修饰war,是为了灵活性,没有那个多精力为了安全性去编码,
 */
class MyWarManage(override val war: War) : WarInfo, KoinComponent {

    override var handComboCards = emptyList<ComboCard>()
        private set
    override var playComboCards = emptyList<ComboCard>()
        private set
    override var canUseCards = emptyList<ComboCard>()
        private set

    override var extCost: Int = 0
    override val warStatus: WarStatus
        get() {
            field.reLoadOnce()
            return field
        }

    private val lifecycleRegisterImpl = LifecycleRegisterImpl()

    inline fun consumeExtCost(extCost: Int, consumeCost: (Int) -> Unit) {
        this.extCost = extCost
        try {
            consumeCost(getCost())
        } finally {
            this.extCost = 0
        }
    }
    val toDieHandler = ToDieHandler(this)
    override val infoMap: Map<String, CardCombinedConfig>


    val matchState = MatchState()
    val pipelineCache = PipelineCache()

    //private val statusReset: StatusReset
    init {
        val warStatus = WarStatus(this)
        loadKoinModules(module {
            //todo-future 用于兼容之前用注入方式,现在改成从上下文获取,减少内存占用
            single { warStatus }
        })
        this.warStatus = warStatus

        loadKoinModules(module {
            single { matchState }
            single { pipelineCache }
        })
        registerLifecycle(matchState)
        registerLifecycle(pipelineCache)

        infoMap = getKoin().get(named("weightInfo"))
        loadKoinModules(module {
            single { lifecycleRegisterImpl } bind LifecycleRegister::class
        })

    }

    override fun registerLifecycle(lifecycle: Any) {
        lifecycleRegisterImpl.register(lifecycle)
    }

    override fun logoutLifecycle(lifecycle: Any) {
        lifecycleRegisterImpl.logout(lifecycle)
    }


    //转化
    fun parseComboCards(cards: List<Card> = getHandCards()): List<ComboCard> {
        return cards.map {
            parseComboCard(it)
        }
    }

    fun parseComboCard(card: Card): ComboCard {
        val combinedConfig = infoMap[card.cardId]
        val configCost = combinedConfig?.weightInfo?.powerWeight ?: 0.0
        // 仅"无配置法术"需要查询初始费用兜底（随从走实时身材、配置走等效费用，均不查数据库）
        val baseCost = if (configCost <= 0.0 && !card.isMinion()) baseCost(card.cardId) else 0
        return ComboCard(
            combinedConfig = combinedConfig,
            card = card,
            baseValue = calcBaseValue(card, combinedConfig, baseCost),
            // T-FO-017：同一份 baseCost 同时喂 baseValue 与 initialCost（等效费锚点），
            // 使填充层 E 与基础分法术分支同源；非「无配置法术」时为 0，等效费分支不读它。
            initialCost = baseCost
        )
    }

    // 初始费用懒缓存（cardId -> 数据库初始 cost），静态值只查一次
    private val cardInfoDao: CardInfoDao by lazy { getKoin().get() }
    private val baseCostCache = ConcurrentHashMap<String, Int>()
    private fun baseCost(cardId: String): Int =
        baseCostCache.getOrPut(cardId) { cardInfoDao.queryCardCostById(cardId) ?: 0 }


    // ══ 决策批次级 RuleEnv（统一生命周期）══
    //
    // 改造前：评估（WeightHandlerDomain.processWeight）与排序（UsePlanBuilder/UsePlanOrderer）
    // 各自 `new WarInfoEnv(warManage)`——同一批决策里两份快照，crossCard 分段管道缓存不共享、
    // WarView 重复计算，且理论上可能出现「评估认为条件命中、排序认为不命中」的口径分叉。
    //
    // 改造后：一个决策批次共享一份。批次 = 「评估 → 排序」这段区间，其间**无任何出牌动作**，
    // 战场快照一致，共享是安全的。
    //
    // **失效契约**（关键）：任何改变战场/手牌的动作都必须失效本快照，否则缓存即脏数据。
    // 两个收口点，二者必须同时维护：
    //   1. [reLoad] —— 手牌/战场重解析；
    //   2. [lin.domain.use.tryUseCard] —— 所有出牌的唯一收口（UseDomain / skillFallbackUse /
    //      ExtCostStrategy 打硬币均经此）。
    // 策略是**保守失效**：宁可多重建一次（少复用一点缓存），绝不复用过期的战场快照。
    //
    // @verify use-intent-model/K-003: 失效点是否完备尚未穷举验证——若新增绕过 tryUseCard/reLoad
    // 的战场变更路径（如直接调 card.action 的牌效回调），必须在此补第三个失效点。

    private var batchRuleEnv: WarInfoEnv? = null

    /** 决策批次的规则求值环境（惰性创建，见上方失效契约）。 */
    val ruleEnv: RuleEnv
        get() = batchRuleEnv ?: WarInfoEnv(this).also { batchRuleEnv = it }

    /** 使当前决策批次快照失效（下次访问 [ruleEnv] 时重建）。 */
    internal fun invalidateRuleEnv() {
        batchRuleEnv = null
    }

    /**
     * todo-future 暂时重新读取数据,性能太差或者有空 改成复杂状态管理
     * 重新加载
     */
    fun reLoad() {
        //todo-future 临时方案,使用手牌改变战场,重新更新数组,但是不知道有没有普适性
        //之前位置在clean方法里
        registryInfo.clear()
        // 手牌/战场重解析 → 决策批次快照失效（见 [ruleEnv] 失效契约）
        invalidateRuleEnv()

        //select 先转换后再过滤考虑存在费用变更情况
        reLoadHandCards()
        // T-021a/D-009：技能平权入池——池 = 可负担手牌 + 技能候选；handComboCards 不动
        //（保住 refreshComboCards 手牌对齐与 cleanWeight 手牌遍历语义）。已用标记随本周期重置。
        skillUsedThisCycle = false
        canUseCards = canUseCardsByCost() + listOfNotNull(skillCandidate())
        reloadPlayComboCards()

    }

    // ══ T-021a/T-021b（D-009）：技能平权入池（技能 = 每回合可用的战场资源，归战况资源层）══
    // 技能作为池内普通候选参与完整竞争（主搜索 + 余费填充）；池外特殊路径（池外填充竞争/尾部直用/
    // emptyResultAction）已随 T-021b 退役，唯一保留的强制使用点 = [skillFallbackUse] 空结果兜底。

    /**
     * 本周期技能已用（池内打出后由 [skillUsedMarker] 回执置位）。随 reLoad 周期重置——
     * replan 重入池后再次尝试会被游戏层「技能一回合一次」拒绝并 unUse 出池，硬防线在游戏层。
     */
    var skillUsedThisCycle: Boolean = false
        private set

    /** 池内技能打出回执钩子（useDomain 执行链触发；尾部直用路径不经过此处，T-021b 收口） */
    private val skillUsedMarker: UseAfterStrategy = object : UseAfterStrategy {
        override fun afterExtAction(context: UseContext, useDomain: UseDomain) {
            skillUsedThisCycle = true
        }
    }

    /**
     * 技能候选：本周期未用 + 实时费可负担才入池。每周期重建 ComboCard——权重/Banned 状态随重建
     * 清零（每轮重新评估，战术条件可变），换英雄（power 变更）也被重建天然吸收，无需缓存比对。
     */
    private fun skillCandidate(): ComboCard? {
        if (skillUsedThisCycle) return null
        val power = getPower() ?: return null
        val skill = parseSkillCard(power)
        return skill.takeIf { it.cost() <= getCost() }?.also {
            it.useAfterStrategy = it.useAfterStrategy.addSafe(skillUsedMarker)
        }
    }

    /**
     * 技能卡解析 + 缺省注入单点（D-009 第 3 条，D-012 修订；T-026 删 SURPLUS_ONLY 注入）：无配置 → 等效费 1 /
     * N=0 全自由（付得起即垫，「空闲≥技能费才垫」由 cost 门天然保证，Q-013 语义不变）——第一轮自然入池竞争
     * （权重 1.0 挤不走高权重手牌牌位），全手牌卡手时由「Empty→skillFallbackUse 强用」简化为「自然入池直接选出」。
     * 配置的技能走正常 infoMap 链；解析链零技能知识。
     */
    private fun parseSkillCard(power: Card): ComboCard {
        val config = infoMap[power.cardId] ?: CardCombinedConfig(
            weightInfo = CardWeightInfo(cardId = power.cardId, powerWeight = 1.0),
        )
        return ComboCard(
            combinedConfig = config,
            card = power,
            baseValue = calcBaseValue(power, config, 0),
        )
    }

    /**
     * T-021b/D-009 第 5 条：空结果兜底（原 SkillFindStrategy.emptyResultAction 语义）——
     * 手牌+技能全无候选时强用技能防空转。「一次机会」：尝试**前置**已用标记（无论成败），
     * 防同周期重复尝试/死循环；失败由 tryUseCard 尾部 unUse 双保险。
     * 强用条件：本周期未试过 + 费用够（tryUseCard/useOption 门）+ 非 Banned——池内平权后
     * Empty 结果结构上已排除「费用够且未用」的技能（它会在池里），此兜底为防御性保留
     * （覆盖未来池构建策略变化）；负分允许强用（空转代价 > 负分损失，沿用原语义）。
     */
    fun skillFallbackUse(): Boolean {
        if (skillUsedThisCycle) return false
        val power = getPower() ?: return false
        skillUsedThisCycle = true // 一次机会，无论成败
        val skill = parseSkillCard(power)
        return tryUseCard(skill)
    }

    fun reLoadHandCards() {
        handComboCards = parseComboCards()
    }


    override fun reloadPlayComboCards() {
        playComboCards = parseComboCards(getPlayCards())
    }

    fun cleanWeight() {
        handComboCards.forEach {
            it.cleanWeight()
        }
    }



    /**
     *
     * 节省性能方式,但是对于不是新增在右边会有问题,复杂策略往往来更多bug
     * 需要配合使用
     * [useCardAndRemove]
     * 出问题就用
     * [reLoad]
     * todo-future  看一下comboCards不清空状态会怎么样,看情况决定是否清空状态
     * 没有操作
     */
    fun refreshComboCards() {
        val handCards = getHandCards()
        if (handCards.size > handComboCards.size) {
            val tempList = mutableListOf<ComboCard>()
            for (i in handComboCards.size until handCards.size) {
                val card = handCards[i]
                tempList.add(parseComboCard(card))
            }
            handComboCards += tempList
        }
    }


    /**
     * 过滤出指定费用的卡牌,默认过滤出当前费用
     * @param cost  费用
     */
    fun canUseCardsByCost(cost: Int = getCost()) = handComboCards.filter { comBoCard ->
        comBoCard.card.cost <= cost
    }


    /**
     * 产生复杂的状态,未经测试
     * 操作并改变ComBoCard状态
     *未更新
     */
    fun useCardAndRemove(comBoCard: ComboCard) {
        if (tryUseCard(comBoCard)) {
            handComboCards -= comBoCard
            canUseCards -= comBoCard
            if (comBoCard.card.cardType == CardTypeEnum.MINION) {
                playComboCards += comBoCard
            }

        }
    }

    private var gameId: String? = null

    fun isStart(): Boolean {
        val me = war.me
        if (me.resources == 1) {
            var isStart = true
            gameId?.run {
                war.me.gameId
            } ?: {
                if (war.me.gameId == gameId) isStart = false
                war.me.gameId
            }
            return isStart
        }
        return false

    }


    private var isCleanWar = false
    var isFull = false
        private set

    /**
     * 重新设置状态
     */
    fun reset() {
        isCleanWar = false
        isFull = false

        //statusReset.reset()
    }
    fun clean() {
        handComboCards = emptyList()
        playComboCards = emptyList()
        canUseCards = emptyList()
        lifecycleRegisterImpl.endRound(this)
    }

    /**
     * 没考虑并发
     */
    override fun cleanPlayByRoundOnce(): Boolean {
        if (!isCleanWar) {
            DeckStrategyUtil.cleanPlay()
            isCleanWar = false
            return true
        }
        return false
    }
    private val registryInfo = HashSet<String>()

    /**
     * 执行过了就返回true
     */
    override fun roundExecuteOnce(registryId: String): Boolean {
        val isExist = registryInfo.contains(registryId)
        if (isExist) {
            return true
        }
        registryInfo.add(registryId)
        return false
    }

    fun processPlayCardIsFull(): Double {
        if (!isFull) {
            isFull = playCardIsFull()
        }
        // 如果战场已满
        if (isFull) {
            // 若已清理过战场则直接返回
            if (isCleanWar) {
                return UnUseWeight
            }
            myLog.info { "随从太多清理一下战场" }

            // 清理战场并更新状态
            cleanPlay()
            isFull = playCardIsFull()
            isCleanWar = true
            if (isFull) return UnUseWeight
        }
        return NotWeight
    }

    /**
     * 生命周期的处理
     */
    private fun lifecycle() {
        lifecycleRegisterImpl.startAllRuleLifecycles(this)
        val isStart = isStart()
        if (isStart) {
            lifecycleRegisterImpl.startAllGameLifecycles()
        }
    }


    /**
     * 策略执行环境
     */
    fun executeEnvironment(runnable: () -> Unit) {
            if (war.isValid()) {
                lifecycle()
                //重新加载信息
                reLoad()
                reset()
                val startNum = getHandCards().size
                //送亡语,送墓场操作
                toDieHandler.processToDie()
                //使用地标
                activeLocation()
                if (startNum > getHandCards().size) reLoad() //重新加载

                runnable()
                //usePower()//使用技能
                myLog.info { "完成所有操作,执行清理战场" }
                //使用地标
                activeLocation()
                //清场
                cleanPlayAll()
                clean()
            } else {
                myLog.warn { "战场无效,不知道为啥会这样" }
            }
    }
}


