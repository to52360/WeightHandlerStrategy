package lin.rule.orthogonal

import lin.rule.context.RuleContext
import lin.rule.context.RuleEnv
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * 数据源 (根节点)：负责从执行环境中提取原始数据集合或对象，不含任何过滤/比较逻辑。
 */
interface DataSource<out T : Any> {
    val id: String
    val name: String
    val description: String

    /**
     * 所属分类 ID 集合（建议使用 [OrthogonalCategoryCatalog] 中的标准分类 ID）
     */
    val categories: Set<String>
    val outputType: KType

    fun resolve(context: RuleContext, env: RuleEnv): T
}

/**
 * 动态数据源的 DSL 构建器
 */
inline fun <reified T : Any> dataSource(
    id: String,
    name: String,
    description: String = "",
    categories: Set<String> = emptySet(),
    crossinline resolver: RuleContext.(RuleEnv) -> T
): DataSource<T> = object : DataSource<T> {
    override val id = id
    override val name = name
    override val description = description
    override val categories = categories
    override val outputType: KType = typeOf<T>()

    override fun resolve(context: RuleContext, env: RuleEnv): T {
        return context.resolver(env)
    }
}
