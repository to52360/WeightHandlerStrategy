package lin.config

import club.xiaojiawei.hsscriptcardsdk.config.DBConfig.CARD_DB_NAME
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

object AppConfig {

    private val logger = LoggerFactory.getLogger(AppConfig::class.java)

    // ---- 加载配置 ----
    private val props: Properties = loadProperties()

    private fun loadProperties(): Properties {
        val defaults = Properties().apply {
            // 从 classpath 加载默认配置
            (javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
                .getResourceAsStream("app.properties")?.use { input ->
                    load(input)
                    logger.info("已加载默认 app.properties (classpath)")
                } ?: logger.warn("classpath 中未找到 app.properties，使用内联默认值")
        }
        // 合并结果
        return Properties().apply {
            // 内联兜底默认值（classpath 文件缺失时）
            setProperty("database.path", "WeightHandlerStrategy/weightHandlerStrategy.db")
            setProperty("hs_cards.db.path", CARD_DB_NAME)
            setProperty("cardgroup.dir.path", "../data/cardgroup")
            // 用 classpath 文件覆盖内联默认值
            putAll(defaults)
            // 外部配置文件覆盖
            System.getProperty("app.config.file")?.let { externalPath ->
                val file = Path.of(externalPath)
                if (Files.isReadable(file)) {
                    Files.newBufferedReader(file).use { reader ->
                        load(reader)
                        logger.info("已加载外部配置文件: $externalPath")
                    }
                } else {
                    logger.warn("外部配置文件不可读: $externalPath")
                }
            }
            // 系统属性覆盖（最高优先级）
            stringPropertyNames().forEach { key ->
                System.getProperty(key)?.let { setProperty(key, it) }
            }
        }
    }

    // ---- 路径解析 ----
    private fun resolvePath(key: String): Path {
        val value = props.getProperty(key)
        return if (value == null) {
            Path.of(".")
        } else {
            val p = Path.of(value)
            if (p.isAbsolute) p else Path.of(userDir, value)
        }
    }

    private val userDir: String = System.getProperty("user.dir")

    // ---- 对外属性 ----
    val databasePath: Path
        get() = resolvePath("database.path")

    val defaultDirPath: Path
        get() = resolvePath("cardgroup.dir.path")

    val hsCardsDbPath: Path
        get() = resolvePath("hs_cards.db.path")
}
