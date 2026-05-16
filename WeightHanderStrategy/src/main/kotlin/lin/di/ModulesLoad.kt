package lin.di

import lin.lifecycle.LifecycleRegister
import lin.lifecycle.LifecycleRegisterImpl
import lin.rule.tree.engineTreeModule
import lin.serviceLoader.module.ModulesInfo
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

class ModulesLoad {
    fun loadModules() {
        startKoin {
            modules(
                dataModule,
                domainModule,
                configModule,
                engineTreeModule
            )
            modules(module { singleOf(::LifecycleRegisterImpl) bind LifecycleRegister::class })
            val extraModule = ServiceLoaderUtils.loadServices(ModulesInfo::class.java)
            extraModule.forEach {
                loadKoinModules(it.loadModules())
            }
        }
    }
}
