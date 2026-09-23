package lin.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * T-FO-009 护栏：`ConfigStore` 的 **classpath 默认层必须真的被加载**。
 *
 * ## 曾经的 bug（2026-09-22 由宿主实况日志暴露）
 *
 * `ConfigStore.load()` 在 `Properties().apply { }` **块内**取 `javaClass` ⇒ Kotlin 解析到接收者
 * `java.util.Properties`（java.base ⇒ `classLoader == null`）⇒ 回落 `getSystemClassLoader()`
 * ——那是宿主 launcher 的 app classpath，**不含引擎 jar** ⇒ classpath 层恒为空，
 * 只在日志留一行 INFO「classpath 中未找到 engine.properties」。
 *
 * 后果不只是"少一层默认值"：`ConfigStore` 只对 **props 里已存在的键** 做系统属性覆盖
 * （`stringPropertyNames().forEach { ... }`）⇒ 键集为空时 **`-D` 覆盖静默失效**。
 * 本测试即锁死"默认层已加载"这个前提——它是 `-D` 覆盖与键注册两件事的事实基础。
 */
class ConfigStoreClasspathDefaultsTest {

    @Test
    fun `classpath 默认层真的被加载`() {
        val store = ConfigStore("engine.properties")
        assertNotNull(
            "engine.properties 的键应能从 classpath 读到（读到 null ⇒ 默认层失效、" +
                    "且 -D 覆盖也无从附着）",
            store.raw("scoring.cost.value.maxCost")
        )
    }

    @Test
    fun `留牌上限键已注册`() {
        assertNotNull(
            "change.keep.cost 未注册 ⇒ 部署侧改不动它（2026-09-22 发现遗漏并补注册）",
            ConfigStore("engine.properties").raw("change.keep.cost")
        )
    }

    @Test
    fun `已注册键可被系统属性覆盖`() {
        val key = "scoring.cost.value.maxCost"
        System.setProperty(key, "42.5")
        try {
            // 覆盖发生在「默认层 → 外部文件 → 系统属性」链末，故此处必为 -D 值
            assertEquals(42.5, ConfigStore("engine.properties").double(key, 10.0), 1e-9)
        } finally {
            System.clearProperty(key)
        }
    }
}
