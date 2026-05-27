package lin.domain


import club.xiaojiawei.hsscriptbasestrategy.util.DeckStrategyUtil
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.isValid
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.ComboCard
import lin.domain.context.NotWeight
import lin.domain.context.UnUseWeight
import lin.domain.use.tryUseCard
import lin.lifecycle.LifecycleRegister
import lin.lifecycle.LifecycleRegisterImpl
import lin.myLog
import lin.serviceLoader.weightRule.utils.war.WarStatus
import lin.warExt.action.activeLocation
import lin.warExt.action.cleanPlay
import lin.warExt.action.cleanPlayAll
import lin.warExt.my.base.getCost
import lin.warExt.my.base.getHandCards
import lin.warExt.my.base.getPlayCards
import lin.warExt.my.base.playCardIsFull
import lin.weightHandler.warHandler.ToDieHandler
import org.koin.core.component.KoinComponent
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module


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

    //private val statusReset: StatusReset
    init {
        val warStatus = WarStatus(this)
        loadKoinModules(module {
            //todo-future 用于兼容之前用注入方式,现在改成从上下文获取,减少内存占用
            single { warStatus }
        })
        this.warStatus = warStatus

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
        return ComboCard(
            combinedConfig = infoMap[card.cardId],
            card = card
        )
    }


    /**
     * todo-future 暂时重新读取数据,性能太差或者有空 改成复杂状态管理
     * 重新加载
     */
    fun reLoad() {
        //todo-future 临时方案,使用手牌改变战场,重新更新数组,但是不知道有没有普适性
        //之前位置在clean方法里
        registryInfo.clear()

        //select 先转换后再过滤考虑存在费用变更情况
        reLoadHandCards()
        canUseCards = canUseCardsByCost()
        reloadPlayComboCards()

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


