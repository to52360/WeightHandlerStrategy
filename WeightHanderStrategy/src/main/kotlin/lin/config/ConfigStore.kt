package lin.config

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

/**
 * 通用解析器：从 classpath / 外部文件 / 系统属性加载 .properties。
 * 上层配置对象各自 new 一个实例指向自己的配置文件，加键永远不改本文件。
 */
class ConfigStore(
    resourceName: String,
    private val externalFileProp: String = "app.config.file"
) {
    private val logger = LoggerFactory.getLogger(ConfigStore::class.java)
    private val props: Properties = load(resourceName)
    private val userDir: String = System.getProperty("user.dir")

    private fun load(resourceName: String): Properties {
        val defaults = Properties().apply {
            (javaClass.classLoader ?: ClassLoader.getSystemClassLoader())
                .getResourceAsStream(resourceName)?.use { input ->
                    load(input)
                    logger.info("已加载 $resourceName (classpath)")
                } ?: logger.info("classpath 中未找到 $resourceName")
        }
        return Properties().apply {
            putAll(defaults)
            System.getProperty(externalFileProp)?.let { externalPath ->
                val file = Path.of(externalPath)
                if (Files.isReadable(file)) {
                    Files.newBufferedReader(file).use { load(it) }
                    logger.info("已加载外部配置文件: $externalPath")
                } else logger.warn("外部配置文件不可读: $externalPath")
            }
            stringPropertyNames().forEach { key ->
                System.getProperty(key)?.let { setProperty(key, it) }
            }
        }
    }

    fun raw(key: String): String? = props.getProperty(key)

    fun double(key: String, default: Double): Double =
        props.getProperty(key)?.toDoubleOrNull() ?: default.also {
            if (props.getProperty(key) != null) logger.warn("配置项 $key 值非法，使用默认值 $default")
        }

    fun long(key: String, default: Long): Long =
        props.getProperty(key)?.toLongOrNull() ?: default.also {
            if (props.getProperty(key) != null) logger.warn("配置项 $key 值非法，使用默认值 $default")
        }

    fun int(key: String, default: Int): Int =
        props.getProperty(key)?.toIntOrNull() ?: default.also {
            if (props.getProperty(key) != null) logger.warn("配置项 $key 值非法，使用默认值 $default")
        }

    fun boolean(key: String, default: Boolean): Boolean =
        props.getProperty(key)?.toBooleanStrictOrNull() ?: default.also {
            if (props.getProperty(key) != null) logger.warn("配置项 $key 值非法，使用默认值 $default")
        }

    fun path(key: String, default: String): Path {
        val value = props.getProperty(key) ?: return Path.of(userDir, default).also {
            logger.warn("配置项 $key 缺失，使用默认值 $default")
        }
        val p = Path.of(value)
        return if (p.isAbsolute) p else Path.of(userDir, value)
    }
}
