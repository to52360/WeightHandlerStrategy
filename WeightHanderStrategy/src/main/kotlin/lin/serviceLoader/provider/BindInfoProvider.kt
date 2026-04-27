package lin.serviceLoader.provider

import lin.config.find.def.BindInfo

/**
 * 用于声明需要修改card的信息
 */
interface BindInfoProvider {
    fun provide(): List<BindInfo>
}