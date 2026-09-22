package lin.domain

import club.xiaojiawei.hsscriptbase.config.log
import lin.myLog
import lin.utils.serviceLoader.ServiceLoaderUtils

/**
 * 横切能力：切换线程上下文类加载器后执行代码块。
 * 用于 SPI 插件 jar 在独立类加载器下加载，与出牌业务无关。
 *
 * 类加载器**复用 [ServiceLoaderUtils] 的单点实例**——本类在 Koin 里注册为 `factory()`，
 * 若各自新建 `JarClassLoader`，每构造一个 ComboDomain 就重扫一次扩展目录并多出一个 URLClassLoader，
 * 与 SPI 发现走的是两套扫描结果（机制必须单点：漏改一处不报错）。
 */
class ClassLoaderScope {
    private val classLoader = ServiceLoaderUtils.classLoader

    fun <T> withContext(runnable: () -> T): T {
        val threadClassLoader = Thread.currentThread().contextClassLoader
        try {
            Thread.currentThread().contextClassLoader = classLoader
            return runnable()
        } catch (t: Throwable) {
            myLog.error(t) { "全局错误捕获" }
            throw t
        } finally {
            Thread.currentThread().contextClassLoader = threadClassLoader
        }
    }
}

/**
 * 出牌递归栈控制：防止 [lin.domain.ComboDomain.useCardAndIsReload] -> reLoad -> findAndUse
 * 重入导致无限递归爆栈。每个 ComboDomain 实例持有独立的控制器。
 */
class ComboCycleController {
    private var stackNum = 0

    fun reset() {
        stackNum = 0
    }

    fun transaction(runnable: () -> Unit) {
        if (stackNum == MaxStackNum) {
            log.warn { "栈过深" }
            return
        }
        stackNum++
        runnable()
    }
}
