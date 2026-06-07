package lin.serviceLoader.provider.config

import lin.bean.usePlan.ComboPlanDefinition

fun interface ComboPlanDefinitionProvider {
    /**
     * 加载新 combo 定义。
     * 定义只描述"组关系、加权、互斥和使用关系"，不负责真实出牌。
     */
    fun findAll(): List<ComboPlanDefinition>
}

