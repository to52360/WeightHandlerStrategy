package lin


import club.xiaojiawei.hsscriptbase.enums.RunModeEnum
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.data.BaseData
import club.xiaojiawei.hsscriptstrategysdk.DeckStrategy
import lin.config.EngineConfig
import lin.di.ModulesLoad
import lin.domain.ComboDomain
import org.koin.core.component.KoinComponent


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
        // ⚠️ 此处**不得** stopKoin()：装配虽已完成，但运行时仍有**懒解析**的 Koin 依赖，
        // 关掉全局容器会让它们抛 `IllegalStateException: KoinApplication has not been started`
        // （2026-09-22 实测出牌线程崩在 MyWarManage.cardInfoDao）。已知两处都在真实出牌路径上：
        //   MyWarManage.cardInfoDao      —— baseCost → 查卡牌初始费用
        //   WeightHandlerDomain.auraBoostEvaluator —— 光环评估
        // 容器随插件实例存活；宿主重复加载本插件时由 ModulesLoad 先关旧容器兜底。
        // （宿主自身不用 Koin——其 lib 与主 jar 内均无 koin 类——故保留全局容器无副作用。）


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
            // T-004：本兜底分支的费用上限须与另外两处同源（原为硬编码 2）。
            // T-FO-011：补一行日志——否则「宿主未启用换牌权重」时整段换牌**完全无记录**。
            myLog.info { "起手换牌: 宿主未启用换牌权重，按费用兜底全换 cost > ${EngineConfig.changeKeepCost}" }
            cards.removeIf { card -> card.cost > EngineConfig.changeKeepCost }
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