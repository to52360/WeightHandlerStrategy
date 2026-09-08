package lin.utils

import lin.config.ConfigStore
import lin.config.EngineConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.*

/**
 * T-PV-002：决策日志开关。
 *
 * 锁三条：
 * 1. **默认关闭**——决策日志是逐卡高频输出，不能污染常规对局日志；
 * 2. **键必须注册进 `engine.properties`**——[ConfigStore] 只对「props 里已存在的键」做 `-D`
 *    系统属性覆盖，漏注册会导致部署侧开了也没用（这是本任务最容易踩的坑）；
 * 3. **注册后 `-D` 覆盖确实生效**——部署侧不改 logback、不重打包即可开启。
 */
class DecisionLogTest {

    @Test
    fun `决策日志默认关闭`() {
        assertEquals(false, EngineConfig.decisionLogEnabled)
    }

    @Test
    fun `决策日志开关键已注册进 engine_properties`() {
        val props = Properties()
        val stream = javaClass.classLoader.getResourceAsStream("engine.properties")
            ?: error("classpath 中未找到 engine.properties")
        stream.use { props.load(it) }

        assertTrue(
            "decision.log.enabled 必须注册进 engine.properties，否则 -D 系统属性覆盖不生效",
            props.containsKey("decision.log.enabled")
        )
    }

    @Test
    fun `已注册的键可被系统属性覆盖`() {
        System.setProperty("decision.log.enabled", "true")
        try {
            val store = ConfigStore("engine.properties")
            assertTrue(
                "-Ddecision.log.enabled=true 应能覆盖默认值（部署侧靠这个开关）",
                store.boolean("decision.log.enabled", false)
            )
        } finally {
            System.clearProperty("decision.log.enabled")
        }
    }
}
