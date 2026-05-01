package lin.dao

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.rule.build.DynamicFieldOption
import lin.serviceLoader.provider.SelectOptionProvider
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

// 根据用户提供的 JSON 格式定义对应的数据类
data class CardGroupConfig(
    val enabled: Boolean,
    val cards: List<CardWeightConfig>
)

data class CardWeightConfig(
    val cardId: String,
    val name: String,
)

object CardGroupJsonParser {
    private val mapper = jacksonObjectMapper().apply {
        // 忽略未知的 JSON 属性，防止因为别人配置多了字段导致解析失败
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    /**
     * 读取指定目录下的所有 JSON 文件，并解析为 CardGroupConfig 列表
     */
    fun loadAllCardGroups(
        dirPath: Path = Path.of(
            System.getProperty("user.dir"),
            "../../data/cardgroup"
        )
    ): List<CardGroupConfig> {
        if (!Files.exists(dirPath)) {
            return emptyList()
        }

        return Files.list(dirPath).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".cardgroup") }
                .map { path ->
                    try {
                        val content = Files.readString(path)
                        mapper.readValue<CardGroupConfig>(content)
                    } catch (e: Exception) {
                        System.err.println("解析 JSON 失败: $path, 错误: ${e.message}")
                        null
                    }
                }
                .toList()
                .filterNotNull()
        }
    }
}

class CardSelectOptionProvider : SelectOptionProvider {
    override val dataSourceId: String = "cardGroup"

    override fun getOptions(): List<DynamicFieldOption> {
        val groups = CardGroupJsonParser.loadAllCardGroups()
        TODO()
    }
}
