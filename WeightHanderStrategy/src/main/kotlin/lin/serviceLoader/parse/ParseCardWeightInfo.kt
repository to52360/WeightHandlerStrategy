package lin.serviceLoader.parse

import lin.bean.CardWeightInfo

/**
 * 用于绑定数据
 * todo-future 接口即将弃用
 * 数据和绑定强耦合,解耦新接口
 * [lin.serviceLoader.provider.BindInfoProvider]
 * 新的参考
 * [lin.config.useDemo.WarriorBindInfo]
 */
@Deprecated("接口即将弃用")
interface ParseCardWeightInfo {
    fun parse(infoMap: Map<String, CardWeightInfo>)
}