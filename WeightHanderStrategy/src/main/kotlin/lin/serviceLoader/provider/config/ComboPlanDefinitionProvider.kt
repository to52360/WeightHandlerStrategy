package lin.serviceLoader.provider.config

import lin.bean.usePlan.ComboPlanDefinition

fun interface ComboPlanDefinitionProvider {
    /**
     * 加载新 combo 定义。
     * 定义只描述"组关系、加权、互斥和使用关系"，不负责真实出牌。
     */
    fun findAll(): List<ComboPlanDefinition>
}

object TodoComboPlanDefinitionProvider : ComboPlanDefinitionProvider {
    /**
     * 空实现用于没有加载 configUi provider 的环境。
     * 正常运行时由 configUi 的 SqliteComboPlanDefinitionProvider 提供配置。
     */
    override fun findAll(): List<ComboPlanDefinition> {
        return emptyList()
    }
}
