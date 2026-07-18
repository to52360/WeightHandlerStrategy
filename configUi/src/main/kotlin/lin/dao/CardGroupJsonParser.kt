package lin.dao

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import lin.config.PathConfig
import lin.db.CardIdNameText
import lin.rule.build.DynamicFieldOption
import lin.serviceLoader.provider.SelectOptionProvider
import lin.ui.card_group.db.CardGroupService
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
    val weight: Double? = null
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
        dirPath: Path = PathConfig.defaultDirPath
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
    fun listAvailableFiles(dirPath: Path = PathConfig.defaultDirPath): List<String> {
        if (!Files.exists(dirPath)) return emptyList()
        return Files.list(dirPath).use { stream ->
            stream.filter { it.isRegularFile() && it.fileName.toString().endsWith(".cardgroup") }
                .map { it.fileName.toString().removeSuffix(".cardgroup") }
                .toList()
        }
    }

    /**
     * 将卡牌列表（通常来自炉石卡组代码解析得到的 [CardIdNameText]）写成一个 `.cardgroup` JSON 文件。
     * 文件写入 [dirPath]（默认 [PathConfig.defaultDirPath]，即 app.properties 中的 cardgroup.dir.path），
     * 文件名为 `$groupName.cardgroup`。目录不存在时自动创建。
     * 返回写入的文件路径。
     */
    fun saveCardGroup(
        cards: List<CardIdNameText>,
        groupName: String,
        enabled: Boolean = true,
        dirPath: Path = PathConfig.defaultDirPath
    ): Path {
        if (cards.isEmpty()) {
            throw IllegalArgumentException("卡牌列表为空，无法生成 .cardgroup 文件")
        }
        if (!Files.exists(dirPath)) {
            Files.createDirectories(dirPath)
        }
        val config = CardGroupConfig(
            enabled = enabled,
            cards = cards.map { CardWeightConfig(cardId = it.cardId, name = it.name) }
        )
        val file = dirPath.resolve("$groupName.cardgroup")
        Files.writeString(file, mapper.writeValueAsString(config))
        return file
    }


    /**
     * 按文件名（不含扩展名）加载单个卡池，供 UI 在编辑分组时回显完整卡池。
     * 找不到文件或解析失败时返回 null。
     */
    fun loadByFileName(
        fileName: String,
        dirPath: Path = PathConfig.defaultDirPath
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

    /** 按 managerId 过滤，仅返回指定方案下的分组 */
    fun getOptionsByManager(managerId: String): List<DynamicFieldOption> {
        val bindings = service.loadBindings(managerId)
        return bindings.map { binding ->
            DynamicFieldOption(
                value = binding.id,
                label = binding.name
            )
        }
    }
}
