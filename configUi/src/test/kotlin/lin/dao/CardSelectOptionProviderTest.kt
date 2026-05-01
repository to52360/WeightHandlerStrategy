package lin.dao

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString

class CardSelectOptionProviderTest {

    @Test
    fun `test getOptions returns distinct enabled weights`() {
        val provider = CardSelectOptionProvider()

        // 确保 dataSourceId 正确
        assertEquals("cardgroup", provider.dataSourceId)

        // 实际调用获取可选项的方法
        // 注意：由于它会读取本地 "../../data/cardgroup" 目录下的文件，
        // 这里的测试会真实依赖于您本地的数据。
        val options = provider.getOptions()

        println("找到的选项数量: ${options.size}")
        options.forEach { option ->
            println("Option - label: ${option.label}, value: ${option.value}")
        }

        // 验证返回的列表不为空（前提是您的 data/cardgroup 目录下有启用的卡组数据）
        assertNotNull(options)

        // 验证所有选项的 value 是否都是唯一的（测试去重逻辑）
        val distinctValues = options.map { it.value }.toSet()
        assertEquals(options.size, distinctValues.size, "返回的权重值应该被去重")

        // 验证标签格式
        if (options.isNotEmpty()) {
            val sample = options.first()
            assertTrue(sample.label.startsWith("权重: "), "Label 应该以 '权重: ' 开头")
        }
    }

    @Test
    fun `test custom json parser loading`() {
        // 如果想单独测试解析器逻辑，可以这样做：
        val dirPath = Path(System.getProperty("user.dir"), "../../data/cardgroup")
        println("尝试从路径读取 JSON: ${dirPath.absolutePathString()}")

        val groups = CardGroupJsonParser.loadAllCardGroups(dirPath)
        println("找到 ${groups.size} 个卡组配置文件")

        groups.forEach { group ->
            println("卡组: ${group.enabled} (已启用: ${group.enabled}), 卡牌数量: ${group.cards.size}")
        }
    }
}
