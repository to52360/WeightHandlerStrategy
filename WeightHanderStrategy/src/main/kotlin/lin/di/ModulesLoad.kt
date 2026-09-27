package lin.di

import lin.lifecycle.LifecycleRegister
import lin.lifecycle.LifecycleRegisterImpl
import lin.myLog
import lin.serviceLoader.module.ModulesInfo
import lin.utils.serviceLoader.ServiceLoaderUtils
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

class ModulesLoad {
    fun loadModules() {
        // 宿主可能重复加载本插件（重载 / 换卡组 / 重开一局）：先关掉上一次的全局容器，
        // 否则 startKoin 会因「已启动」直接抛 KoinApplicationAlreadyStartedException。
        // 注意容器**不在装配后关闭**（见 WeightHandlerStrategy.init 的说明：运行时仍有懒解析依赖）。
        if (GlobalContext.getOrNull() != null) {
            myLog.debug { "检测到已存在的 Koin 容器，先关闭再重新装载（插件重载）" }
            stopKoin()
        }
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
            if (extraModule.isEmpty()) {
                // 独立点名：无扩展时上层只会看到 Koin 的 NoDefinitionFoundException + 各 Provider 静默降级
                // （combo 空定义 / 时序规则回落硬编码），从这里看不出与「扩展 jar 放错目录」有关。
                myLog.warn {
                    "未发现 ModulesInfo 扩展：配置侧能力（评估树/条件树/光环/combo/卡组绑定/用途标签/时序规则）将整体缺席。 " +
                            ServiceLoaderUtils.diagnose()
                }
            }
            extraModule.forEach {
                loadKoinModules(it.loadModules())
            }
        }
        // 执行启动任务（D-FO-002 / D-FO-009：过程不进容器 ⇒ 直接 new；依赖仍从容器取）
        StartupPlan.startupTasks().forEach { it.execute() }
    }
}
