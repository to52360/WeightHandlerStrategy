package lin.serviceLoader.parse

import lin.bean.CardWeightInfo

/**
 * 用于绑定数据
 * todo-future 接口即将弃用
 * 数据和绑定强耦合,解耦新接口
 * 新的参考见 [lin.serviceLoader.provider.BindInfoProvider]
 */
@Deprecated("接口即将弃用")
interface ParseCardWeightInfo {
    fun parse(infoMap: Map<String, CardWeightInfo>)
}