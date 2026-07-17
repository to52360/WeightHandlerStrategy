package lin.bean


import lin.config.CardType
import lin.domain.context.NotWeight
import lin.lifecycle.LifecycleRegister

import lin.rule.tree.EvaluatorInstanceNode

import lin.serviceLoader.weightRule.WeightRule
import org.koin.core.component.KoinComponent
import org.koin.core.component.get


const val DefUseGroupOrder = 10.0

const val COINGroupId = 1
const val ChangeGroupId = 10
const val CleanWarId = 15

const val DefUseGroupId = 25
const val LastUseGroupId = 30
const val FirstUseGroupId = 20

/**
 * 转化位置
 * [lin.serviceLoader.cardInfoProvide.DefCardWeightInfoProvide]
 * @param groupId 使用weight的值 [club.xiaojiawei.hsscriptcardsdk.bean.CardWeight.weight]
 * @param powerWeight 检测优先级
 *
 * todo-future (三合一了)信息太多可以拆分.集合类的变量应该添加处理上下文(操作日志和处理器之间的通信)
 *
 */
data class CardWeightInfo(
    val cardId: String,
    val powerWeight: Double,
    val groupId: Double = 1.0,
    val changeWeight: Double = NotWeight
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



    private var _intentEvaluatorRoots: MutableList<EvaluatorInstanceNode>? = null

    val intentEvaluatorRoots: List<EvaluatorInstanceNode>
        get() = _intentEvaluatorRoots ?: emptyList()

    fun addIntentEvaluatorRoot(root: EvaluatorInstanceNode) {
        _intentEvaluatorRoots = _intentEvaluatorRoots.addSafe(root)
    }


    //使用相关：use 策略统一走 combinedConfig（UseConfigHandler 写入），CardWeightInfo 不再持有

    /**
     *  combo相关
     *  最后使用暂时这样,没想到其他方案
     *  升序
     */
    var useGroupId = DefUseGroupId
    var useGroupOrder = DefUseGroupOrder

    private var _combos: MutableList<Combo>? = null

    val combos: List<Combo>?
        get() = _combos

    fun addCombo(combo: Combo) {
        _combos = _combos.addSafe(combo)
    }

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




