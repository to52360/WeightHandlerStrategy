package lin.config.useDemo

import lin.bean.ChangeGroupId
import lin.config.UseConfig
import lin.config.find.def.BindInfo
import lin.serviceLoader.provider.BindInfoProvider

/**
 * 任务战的绑定
 */
class WarriorBindInfo : BindInfoProvider {
    override fun provide(): List<BindInfo> {
        return listOf(BindInfo("ETC_417", UseConfig(ChangeGroupId - 1)))
    }

}