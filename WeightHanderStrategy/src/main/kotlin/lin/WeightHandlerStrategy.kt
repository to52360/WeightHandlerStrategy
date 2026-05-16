package lin


import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import lin.di.ModulesLoad
import lin.domain.ComboDomain
import org.koin.core.component.KoinComponent
import org.koin.core.context.stopKoin


/**
 * @see club.xiaojiawei.hsscriptcardsdk.bean.BaseCard
 * @see club.xiaojiawei.hsscriptcardsdk.bean.Player
 * 插件管理
 * 参考[HsRadicalDeckStrategy]
 * 权重表[CARD_WEIGHT_TRIE]
 * WeightHandlerPlugin
 */
class WeightHandlerStrategy : DeckStrategy(), KoinComponent {
    private val comboDomain: ComboDomain


    init {
        myLog.info{
            "执行策略初始化"
        }
        ModulesLoad().loadModules()
        comboDomain = ComboDomain()
        stopKoin()


    }


    override fun name(): String = "权重处理策略"

    override fun description(): String = "基于战场计算权重的策略,例如在手牌对应种族就加权重,通过配置绑定到组,然后通过组id关联到权重表(CardWeight)的weight,依赖数据也是\n"

    override fun getRunMode(): Array<RunModeEnum> =
        arrayOf(RunModeEnum.CASUAL, RunModeEnum.STANDARD, RunModeEnum.WILD, RunModeEnum.PRACTICE)

    override fun deckCode(): String = ""

    override fun id(): String = "e71234fa-1-weightHandler-deck-97e9-1f4e126cd33b"

    override fun referWeight(): Boolean = true

    override fun referPowerWeight(): Boolean = true

    override fun referChangeWeight(): Boolean = true

    /**
     * [HsRadicalDeckStrategy]
     * 参考
     * [DeckStrategyUtil.convertToSimulateCard]
     */
    override fun executeChangeCard(cards: HashSet<Card>) {
        if (BaseData.enableChangeWeight) {
            comboDomain.executeChangeCard(cards)
        } else {
            cards.removeIf { card -> card.cost > 2 }
        }
    }



    override fun executeOutCard() {
            comboDomain.outCardStrategy()


    }

    /**
     * todo-future 发现策略选择
     */

    override fun executeDiscoverChooseCard(vararg cards: Card): Int  {

        if (cards.size < 2) return 0
          return   comboDomain.executeDiscoverChooseCard(*cards)
    }
}