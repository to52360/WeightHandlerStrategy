package lin.di

import lin.lifecycle.LifecycleRegister
import lin.lifecycle.LifecycleRegisterImpl
import lin.serviceLoader.module.ModulesInfo
import lin.serviceLoader.provider.StartupTask
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

class ModulesLoad {
    fun loadModules() {
        val koinApp = startKoin {
            modules(
                dataModule,
                domainModule,
                configModule,
                infraModule,
                ruleModule
            )
            modules(module { singleOf(::LifecycleRegisterImpl) bind LifecycleRegister::class })
            val extraModule = ServiceLoaderUtils.loadServices(ModulesInfo::class.java)
            extraModule.forEach {
                loadKoinModules(it.loadModules())
            }
        }
        // 执行启动任务，如配置装配与规则树绑定
        koinApp.koin.getAll<StartupTask>().forEach { it.execute() }
    }
}
