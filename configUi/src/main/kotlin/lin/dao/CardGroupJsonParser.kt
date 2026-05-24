package lin.dao

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.card_group.db.CardGroupService
import lin.rule.build.DynamicFieldOption
import lin.serviceLoader.provider.SelectOptionProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
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
    private val mapper = jacksonObjectMapper()
        // 忽略未知的 JSON 属性，防止因为别人配置多了字段导致解析失败
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)


    /**
     * 读取指定目录下的所有 JSON 文件，并解析为 CardGroupConfig 列表。
     * 返回 Pair<fileName(不含扩展名), CardGroupConfig>。
     */
    fun loadAllCardGroups(
        dirPath: Path = defaultDirPath()
    ): List<Pair<String, CardGroupConfig>> {
        if (!Files.exists(dirPath)) return emptyList()

        return Files.list(dirPath).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".cardgroup") }
                .map { path ->
                    try {
                        val content = Files.readString(path)
                        val config = mapper.readValue<CardGroupConfig>(content)
                        val fileName = path.fileName.toString().removeSuffix(".cardgroup")
                        fileName to config
                    } catch (e: Exception) {
                        System.err.println("解析 JSON 失败: $path, 错误: ${e.message}")
                        null
                    }
                }
                .toList()
                .filterNotNull()
        }
    }

    /** 仅列出该目录下所有的 .cardgroup 文件名（不含扩展名），不解析文件内容 */
    fun listAvailableFiles(dirPath: Path = defaultDirPath()): List<String> {
        if (!Files.exists(dirPath)) return emptyList()
        return Files.list(dirPath).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".cardgroup") }
                .map { it.fileName.toString().removeSuffix(".cardgroup") }
                .toList()
        }
    }


    /**
     * 按文件名（不含扩展名）加载单个卡池，供 UI 在编辑分组时回显完整卡池。
     * 找不到文件或解析失败时返回 null。
     */
    fun loadByFileName(
        fileName: String,
        dirPath: Path = defaultDirPath()
    ): CardGroupConfig? {
        val file = dirPath.resolve("$fileName.cardgroup")
        if (!Files.exists(file)) return null
        return try {
            mapper.readValue<CardGroupConfig>(Files.readString(file))
        } catch (e: Exception) {
            System.err.println("加载卡牌文件失败: $file, 错误: ${e.message}")
            null
        }
    }

    private fun defaultDirPath(): Path =
        Path.of(System.getProperty("user.dir"), "../../data/cardgroup")
}

/**
 * 返回该卡池中所有可选的 [CardWeightConfig]。
 * 当 [CardGroupConfig.enabled] 为 false 时卡池仍返回，由 UI 决定是否提示已禁用。
 */
val CardGroupConfig.availableCards: List<CardWeightConfig>
    get() = cards

/**
 * 修正后的数据提供者：从 DB/Service 中获取所有已启用的 Manager 下的分组（Binding）
 */
class CardSelectOptionProvider : SelectOptionProvider, KoinComponent {
    override val dataSourceId: String = "cardGroup"
    private val service: CardGroupService by inject()

    override fun getOptions(): List<DynamicFieldOption> {
        // 只加载已启用的 Manager 方案
        val managers = service.loadAll(onlyEnabled = true)

        // 展平所有方案下的具体分组（Binding）
        return managers.flatMap { manager ->
            manager.bindings.map { binding ->
                DynamicFieldOption(
                    value = binding.id,
                    label = binding.name
                )
            }
        }
    }
}
