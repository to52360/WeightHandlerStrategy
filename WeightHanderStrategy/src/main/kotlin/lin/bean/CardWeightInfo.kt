package lin.bean


import lin.config.CardType
import lin.config.EvaluatorTreeRoot
import lin.domain.context.NotWeight
import lin.lifecycle.LifecycleRegister

import lin.serviceLoader.weightRule.WeightRule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get


const val DefUseGroupOrder = 10.0

const val ChangeGroupId = 10
const val CleanWarId = 15

const val DefUseGroupId = 25
const val LastUseGroupId = 30
const val FirstUseGroupId = 20

/**
 * 转化位置
 * [lin.serviceLoader.cardInfoProvide.DefCardWeightInfoProvide]
 * @param groupId 使用weight的值 [club.xiaojiawei.hsscriptcardsdk.bean.CardWeight.weight]
 * @param powerWeight 配置等效费用（D-014 原语义：卡牌**固有**等效费用，**不含**战术溢价；如 6 费牌等效 5 = 略亏模）：
 *   >0 表示显式配置，基础价值 = costValue(powerWeight)（费用价值凹函数）；=0 表示无配置，走身材/法术兜底。
 *   **D-007 小数位编码 v3（phase-1，存储侧约定）**：上游存储把「等效费(整数位) + 空闲放行门槛 N(小数位)」压在同一个数里
 *   （如 5.4 = 等效 5 费 / 垫后余量 4 费，D-012），由 DefCardWeightInfoProvide 解码拆分为
 *   本字段（floor 后整数）+ [surplusIdleThreshold]。本字段经解码后恒为整数。
 * @param surplusIdleThreshold 空闲放行门槛 N（余费门槛，D-007 v3）：空闲费 ≥ N 才允许垫牌放行；
 *   null = 未配置 = 0 付得起即垫（D-005「无战术不死捏」）。解码自 powerWeight 小数位，取值 1~9。
 *   垫的价值 fillValue = 等效费 + 树分×scale（战术溢价另算，不占本编码位）。
 *   「不贪心」下调（本来 4 费放行的卡 2 费就考虑）= 直接配更小的 N；
 *   局面动态调 N 暂无载体（Q-022）；死捏 = Banned（评估树）或大 N。
 *
 * todo-future (三合一了)信息太多可以拆分.集合类的变量应该添加处理上下文(操作日志和处理器之间的通信)
 *
 */
data class CardWeightInfo(
    val cardId: String,
    val powerWeight: Double,
    val groupId: Double = 1.0,
    val changeWeight: Double = NotWeight,
    val surplusIdleThreshold: Int? = null
) : KoinComponent {

    // 卡牌类型集合
    private var _cardTypes: HashSet<CardType>? = null
    val cardTypes: Set<CardType>
        get() = _cardTypes ?: emptySet()

    fun addCardType(cardType: CardType) {
        _cardTypes = _cardTypes.addSafeToSet(cardType)
    }

    fun isCardType(cardType: CardType): Boolean {
        return _cardTypes?.contains(cardType) ?: false
    }

    private var _weightRules: MutableList<WeightRule>? = null

    //todo-future 应该移到ConditionHandler,为了一点性能增加复杂性不可取
    val weightRules: List<WeightRule>
        get() = _weightRules ?: emptyList()


    /**
     * 添加权重规则
     * 会额外判断是否需要注册到
     */
    fun addWeightRule(weightRule: WeightRule){
        setWeightRule(weightRule)
        val lifecycleRegister = get<LifecycleRegister>()
        //生命周期,游戏开始/回合开始结束调用对应方法,为了条件组有状态
        lifecycleRegister.register(weightRule)
    }

    //只是简单的添加
    fun setWeightRule(weightRule: WeightRule) {
        _weightRules = _weightRules.addSafe(weightRule)
    }
    fun setWeightRules(weightRules: MutableList<WeightRule>) {
        _weightRules?.addAll(weightRules) ?: run {
            _weightRules = weightRules
        }
    }


    private var _intentEvaluatorRoots: MutableList<EvaluatorTreeRoot>? = null

    /** 评估树根。绑定任务实例化后注入。 */
    val intentEvaluatorRoots: List<EvaluatorTreeRoot>
        get() = _intentEvaluatorRoots ?: emptyList()

    fun addIntentEvaluatorRoot(root: EvaluatorTreeRoot) {
        _intentEvaluatorRoots = _intentEvaluatorRoots.addSafe(root)
    }


    //使用相关：use 策略统一走 combinedConfig（UseConfigHandler 写入），CardWeightInfo 不再持有

    /**
     *  T-002：旧排序通道弃用（已全切 UseStage/UsePlanOrderer）。
     *  运行时零读者（硬币识别已迁 EXTRA_COST 标签通道，T-004）；Q-003 收口（2026-09-02）：
     *  COINProvide 写入与 COINGroupId 常量已删，字段仅为旧 DB 数据反序列化兼容保留。
     *  combo相关
     */
    var useGroupId = DefUseGroupId
    var useGroupOrder = DefUseGroupOrder

    var cardContext: CardContext? = null


    //换牌相关

    private var _changeComboRule: MutableList<ComboRule>? = null
    val changeComboRule: List<ComboRule>
        get() = _changeComboRule ?: emptyList()

    fun addChangeComboRule(comboRule: ComboRule) {
        _changeComboRule = _changeComboRule.addSafe(comboRule)
    }

    //战场相关
    var toDie = false

}

// 扩展函数：安全添加元素到可空列表
fun <T> MutableList<T>?.addSafe(item: T): MutableList<T> {
    return this?.apply { add(item) } ?: mutableListOf(item)
}

fun <T> HashSet<T>?.addSafeToSet(item: T): HashSet<T> {
    return this?.apply { add(item) } ?: hashSetOf(item)
}

fun <T : Any> CardContext?.addSafe(key: MetadataKey<T>, item: T): CardContext {
    val cardContext = this ?: CardContext()
    cardContext.putMetadata(key, item)
    return cardContext
}

class CardContext {
    //元数据 用来存储
    private val metadata: MutableMap<MetadataKey<*>, Any> = hashMapOf()
    fun <T : Any> putMetadata(key: MetadataKey<T>, value: T) {
        metadata[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getMetadata(key: MetadataKey<T>): T? = metadata[key] as T?
}

@JvmInline
value class MetadataKey<T>(val name: String)




