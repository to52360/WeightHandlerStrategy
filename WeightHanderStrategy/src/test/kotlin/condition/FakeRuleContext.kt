package condition

import club.xiaojiawei.hsscriptcardsdk.bean.Card
import club.xiaojiawei.hsscriptcardsdk.bean.Player
import club.xiaojiawei.hsscriptcardsdk.bean.War
import club.xiaojiawei.hsscriptcardsdk.enums.CardRaceEnum
import club.xiaojiawei.hsscriptcardsdk.enums.CardTypeEnum
import lin.bean.CardCombinedConfig
import lin.bean.ComboCard
import lin.domain.MatchState
import lin.domain.WarInfo
import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import lin.rule.context.WarView
import lin.rule.context.toWarView
import lin.serviceLoader.weightRule.utils.war.WarStatus
import sun.misc.Unsafe

private val unsafe: Unsafe by lazy {
    val field = Unsafe::class.java.getDeclaredField("theUnsafe")
    field.isAccessible = true
    field.get(null) as Unsafe
}

fun createMockCard(
    cardId: String = "CS2_029",
    cardType: CardTypeEnum = CardTypeEnum.MINION,
    cost: Int = 1,
    atc: Int = 1,
    health: Int = 1,
    isTaunt: Boolean = false,
    cardRace: CardRaceEnum = CardRaceEnum.UNKNOWN
): Card {
    val card = unsafe.allocateInstance(Card::class.java) as Card

    // Set Entity fields
    setField(card, "cardId", cardId, superClassLevel = 2)
    setField(card, "entityId", "entity_" + java.util.UUID.randomUUID().toString(), superClassLevel = 2)

    // Set BaseCard fields
    setField(card, "cardType", cardType, superClassLevel = 1)
    setField(card, "cost", cost, superClassLevel = 1)
    setField(card, "atc", atc, superClassLevel = 1)
    setField(card, "health", health, superClassLevel = 1)
    setField(card, "isTaunt", isTaunt, superClassLevel = 1)
    setField(card, "cardRace", cardRace, superClassLevel = 1)

    return card
}

fun createMockWar(
    handCards: List<Card> = emptyList(),
    playCards: List<Card> = emptyList(),
    rivalPlayCards: List<Card> = emptyList(),
    meHero: Card? = null,
    meResource: Int? = null
): War {
    val war = unsafe.allocateInstance(War::class.java) as War
    val me = unsafe.allocateInstance(Player::class.java) as Player
    val rival = unsafe.allocateInstance(Player::class.java) as Player

    setField(war, "me", me, superClassLevel = 1)
    setField(war, "rival", rival, superClassLevel = 1)

    val meHand = unsafe.allocateInstance(Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.area.HandArea"))
    val mePlay = unsafe.allocateInstance(Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.area.PlayArea"))
    val meDeck = unsafe.allocateInstance(Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.area.DeckArea"))

    setField(me, "handArea", meHand)
    setField(me, "playArea", mePlay)
    setField(me, "deckArea", meDeck)

    val rivalHand = unsafe.allocateInstance(Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.area.HandArea"))
    val rivalPlay = unsafe.allocateInstance(Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.area.PlayArea"))
    val rivalDeck = unsafe.allocateInstance(Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.area.DeckArea"))

    setField(rival, "handArea", rivalHand)
    setField(rival, "playArea", rivalPlay)
    setField(rival, "deckArea", rivalDeck)

    setAreaCards(meHand, handCards)
    setAreaCards(mePlay, playCards)
    setAreaCards(rivalPlay, rivalPlayCards)
    meHero?.let { setField(mePlay, "hero", it) }
    meResource?.let { setField(me, "resources", it) }

    return war
}

fun createMockWarInfo(
    handCards: List<Card> = emptyList(),
    playCards: List<Card> = emptyList(),
    rivalPlayCards: List<Card> = emptyList(),
    handComboCards: List<ComboCard> = emptyList(),
    meHero: Card? = null,
    meResource: Int? = null
): WarInfo {
    val mockWar = createMockWar(handCards, playCards, rivalPlayCards, meHero, meResource)
    return object : WarInfo {
        override val war: War = mockWar
        override val handComboCards: List<ComboCard> = handComboCards
        override val canUseCards: List<ComboCard> = emptyList()
        override val playComboCards: List<ComboCard> = emptyList()
        override val infoMap: Map<String, CardCombinedConfig> = emptyMap()
        override val extCost: Int = 0
        override val warStatus: WarStatus get() = throw UnsupportedOperationException()
        override fun cleanPlayByRoundOnce(): Boolean = false
        override fun roundExecuteOnce(registryId: String): Boolean = false
        override fun reloadPlayComboCards() {}
        override fun registerLifecycle(lifecycle: Any) {}
        override fun logoutLifecycle(lifecycle: Any) {}
    }
}

private fun setField(obj: Any, fieldName: String, value: Any?, superClassLevel: Int = 0) {
    var clazz = obj.javaClass
    for (i in 0 until superClassLevel) {
        clazz = clazz.superclass
    }
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
        } catch (e: NoSuchFieldException) {
            clazz = clazz.superclass
        }
    }
    if (field != null) {
        field.isAccessible = true
        val listClass = Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.MutableCardList")
        val listInstance = listClass.getDeclaredConstructor().newInstance()
        if (listInstance is MutableCollection<*>) {
            @Suppress("UNCHECKED_CAST")
            (listInstance as MutableCollection<Card>).addAll(cards)
        }
        field.set(areaObj, listInstance)
    }
}

val defaultMockWarInfo = createMockWarInfo(
    handCards = List(5) { createMockCard(cardType = CardTypeEnum.SPELL) }
)

val FakeRuleContext: RuleContext = RuleContext(ComboCard(card = createMockCard()))

val FakeRuleEnv = fakeRuleEnv(defaultMockWarInfo)

fun fakeRuleEnv(warInfo: WarInfo): RuleEnv = object : RuleEnv {
    private val evalCache = mutableMapOf<String, Any?>()

    override fun warInfo(): WarInfo = warInfo
    override fun warView(): WarView = warInfo.toWarView()
    override fun matchState() = MatchState()

    @Suppress("UNCHECKED_CAST")
    override fun <T> cache(key: String, compute: () -> T): T =
        evalCache.getOrPut(key) { compute() } as T
}

fun createMockWarWithGraveyard(graveyardCards: List<Card>): War {
    val war = createMockWar()
    val graveyardArea = unsafe.allocateInstance(
        Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.area.GraveyardArea")
    )
    val meField = findField(War::class.java, "me")
    meField.isAccessible = true
    val me = meField.get(war) as Player

    val graveField = findField(Player::class.java, "graveyardArea")
    graveField.isAccessible = true
    graveField.set(me, graveyardArea)

    // 设置墓地卡牌列表
    val cardsField = findField(graveyardArea.javaClass, "cards")
    cardsField.isAccessible = true
    val listClass = Class.forName("club.xiaojiawei.hsscriptcardsdk.bean.MutableCardList")
    val listInstance = listClass.getDeclaredConstructor().newInstance()
    if (listInstance is MutableCollection<*>) {
        @Suppress("UNCHECKED_CAST")
        (listInstance as MutableCollection<Card>).addAll(graveyardCards)
    }
    cardsField.set(graveyardArea, listInstance)

    return war
}

/** 沿继承链向上查找字段（兼容不同 SDK 版本字段层级差异） */
private fun findField(clazz: Class<*>, name: String): java.lang.reflect.Field {
    var current: Class<*>? = clazz
    while (current != null && current != Any::class.java) {
        try {
            return current.getDeclaredField(name)
        } catch (_: NoSuchFieldException) {
            current = current.superclass
        }
    }
    throw NoSuchFieldException("$name not found in ${clazz.name} or its superclasses")
}
