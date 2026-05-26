package lin.domain.use.plan

data class ComboPlanDefinition(
    val id: String,
    val coreGroupIds: Set<String>,
    val depGroupIds: Set<String>,
    val score: Double = 0.0,
    val coreMutex: Boolean = true,
    val relation: ComboRelation = ComboRelation.SCORE_ONLY
)

enum class ComboRelation {
    SCORE_ONLY,
    CORE_BEFORE_DEP,
    DEP_BEFORE_CORE,
    TOGETHER
}

fun interface ComboPlanDefinitionProvider {
    /**
     * 加载新 combo 定义。
     * 定义只描述“组关系、加权、互斥和使用关系”，不负责真实出牌。
     */
    fun findAll(): List<ComboPlanDefinition>
}

object TodoComboPlanDefinitionProvider : ComboPlanDefinitionProvider {
    /**
     * 占位实现，后续再决定从 DB、配置文件还是 UI 配置读取。
     */
    override fun findAll(): List<ComboPlanDefinition> {
        TODO("从新 combo 配置源加载 ComboPlanDefinition")
    }
}
