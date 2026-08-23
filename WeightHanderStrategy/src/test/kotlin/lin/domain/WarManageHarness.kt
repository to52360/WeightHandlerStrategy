package lin.domain

import club.xiaojiawei.hsscriptcardsdk.CardAction
import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.bean.area.PlayArea
import condition.createMockCard
import condition.createMockWar
import lin.bean.CardCombinedConfig
import lin.bean.CardWeightInfo
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * T-022 战场测试 harness（D-009 前置）：以**真实 [MyWarManage]** 驱动技能链路，
 * 替代 `Unsafe.allocateInstance` 绕构造的 mock 方式。
 *
 * 可注入：手牌（handArea.cards）/ 费用（Player.resources）/ 技能（PlayArea.power）/
 * 卡牌配置（Koin "weightInfo" 底层为同一 ConcurrentHashMap，构造后仍可增改）。
 * 可驱动：[reLoad]（真实重建 handComboCards/canUseCards + 清 roundExecuteOnce registry）、
 * parseComboCard / canUseCardsByCost / roundExecuteOnce 等全部真实逻辑。
 *
 * 用法：@Before 调 [start]，@After 调 [stop]（Koin 生命周期与测试方法对齐）。
 * 注意：parseComboCard 对「无配置的非随从」会懒查 CardInfoDao（依赖 DB）——
 * 测试中的技能/法术卡必须先 [config] 注册配置，或保证其为随从。
 */
class WarManageHarness {

    private val weightConfigs = ConcurrentHashMap<String, CardCombinedConfig>()

    lateinit var warManage: MyWarManage
        private set

    fun start(
        handCards: List<Card> = emptyList(),
        powerCard: Card? = null,
        usableResource: Int = 10,
    ) {
        startKoin {
            modules(module {
                // 显式以 Map 类型注册（MyWarManage 按 Map 类型令牌解析，ConcurrentHashMap 令牌不匹配）
                single<Map<String, CardCombinedConfig>>(named("weightInfo")) { weightConfigs }
            })
        }
        warManage = MyWarManage(buildWar(handCards, powerCard))
        setUsableResource(usableResource)
        // 资源须在 reLoad 前就位：reLoad 会按当前费用过滤池（含 T-021a 技能候选）
        warManage.reLoad()
    }

    fun stop() {
        stopKoin()
    }

    /** 注册卡牌配置（须在 parseComboCard 前调用；infoMap 引用同一 map，晚注册同样生效） */
    fun config(card: Card, weightInfo: CardWeightInfo) {
        weightConfigs[card.cardId] = CardCombinedConfig(weightInfo = weightInfo)
    }

    /** 直接改当前费用（改 Player.resources；不触 reLoad，调用方按需重载） */
    fun setUsableResource(n: Int) {
        val me = readField(warManage.war, "me", superClassLevel = 1) as Player
        setField(me, "resources", n)
        setField(me, "usedResources", 0)
        setField(me, "maxResources", n)
    }

    /** 换技能卡（换英雄场景）；须随后调 [reLoad] 开新一轮 registry */
    fun setPower(card: Card?) {
        val me = readField(warManage.war, "me", superClassLevel = 1) as Player
        (readField(me, "playArea") as PlayArea).power = card
    }

    /** 替换手牌区内容；须随后调 [reLoad] */
    fun setHandCards(cards: List<Card>) {
        val me = readField(warManage.war, "me", superClassLevel = 1) as Player
        setAreaCards(readField(me, "handArea")!!, cards)
    }

    fun reLoad() = warManage.reLoad()

    // ────────────────────────────────────────────────────────────

    private fun buildWar(handCards: List<Card>, powerCard: Card?): War {
        val war = createMockWar(handCards = handCards)
        if (powerCard != null) {
            val me = readField(war, "me", superClassLevel = 1) as Player
            (readField(me, "playArea") as PlayArea).power = powerCard
        }
        return war
    }

    private fun readField(obj: Any, fieldName: String, superClassLevel: Int = 0): Any? {
        var clazz = obj.javaClass
        repeat(superClassLevel) { clazz = clazz.superclass }
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(obj)
    }

    private fun setField(obj: Any, fieldName: String, value: Any?, superClassLevel: Int = 0) {
        var clazz = obj.javaClass
        repeat(superClassLevel) { clazz = clazz.superclass }
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(obj, value)
    }

    private fun setAreaCards(areaObj: Any, cards: List<Card>) {
        var clazz: Class<*>? = areaObj.javaClass
        var field: java.lang.reflect.Field? = null
        while (clazz != null && clazz != Any::class.java) {
            try {
                field = clazz.getDeclaredField("cards")
                break
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        check(field != null) { "area.cards 字段未找到: ${areaObj.javaClass}" }
        field.isAccessible = true
        val listClass = Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.MutableCardList")
        val listInstance = listClass.getDeclaredConstructor().newInstance()
        check(listInstance is MutableCollection<*>) { "MutableCardList 不是 MutableCollection" }
        @Suppress("UNCHECKED_CAST")
        (listInstance as MutableCollection<Card>).addAll(cards)
        field.set(areaObj, listInstance)
    }

    companion object {
        /** 造技能卡（HERO_POWER + 执行记录 action + 必备配置，防 parseComboCard 懒查 DB） */
        fun powerCard(
            cardId: String = "TEST_POWER",
            cost: Int = 2,
        ): Pair<Card, RecordingCardAction> {
            val action = RecordingCardAction()
            val card = createMockCard(
                cardId = cardId,
                cardType = club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum.HERO_POWER,
                cost = cost
            )
            card.action = action
            action.belongCard = card // power() 日志链解引用 belongCard，防 NPE
            return card to action
        }
    }
}

/**
 * 执行记录版 [CardAction]：power 系列动作落地即计数（父类 final power() 内部调 execPower 抽象钩子），
 * 供「Banned 技能是否被强用」「重复用技」类断言使用。其余动作（攻击/指向/交易等）只记录不执行。
 */
class RecordingCardAction : CardAction(false) {
    val powerAttempts = AtomicInteger()
    val otherAttempts = AtomicInteger()

    private fun markPower(): Boolean {
        powerAttempts.incrementAndGet()
        return true
    }

    private fun markOther(): Boolean {
        otherAttempts.incrementAndGet()
        return true
    }

    override fun execPower(): Boolean = markPower()
    override fun execPower(card: Card): Boolean = markPower()
    override fun execPower(position: Int): Boolean = markPower()
    override fun execAttack(card: Card): Boolean = markOther()
    override fun execAttackHero(): Boolean = markOther()
    override fun execPointTo(card: Card, click: Boolean): Boolean = markOther()
    override fun execPointTo(position: Int, hold: Boolean): Boolean = markOther()
    override fun execLClick(): Boolean = markOther()
    override fun execLaunch(): Boolean = markOther()
    override fun execTrade(): Boolean = markOther()
    override fun execChooseOne(index: Int): Boolean = markOther()
    override fun execForge(): Boolean = markOther()
    override fun createNewInstance(): CardAction = RecordingCardAction()
    override fun getCardId(): Array<String> = arrayOf("TEST_RECORDING_ACTION")
}
