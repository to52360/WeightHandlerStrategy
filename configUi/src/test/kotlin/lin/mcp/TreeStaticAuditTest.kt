package lin.mcp

import lin.ai.config.AiConfigGenerationService
import lin.ai.config.SaveEvaluatorTreeRequest
import lin.moduls.loadMcpModules
import lin.repository.condition_tree.ConditionTreeConfigService
import lin.ui.condition_tree.validation.ConditionTreeValidator
import lin.ui.service.TreeConfigService
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.nio.file.Path

/**
 * 落库树静态审计（T-029③ 定义的查法，T-030 验收复用）。
 *
 * 查法：把目标库当只读审计对象——加载全部评估树 + 条件树，逐棵跑保存侧同款校验：
 * - 评估树走 [AiConfigGenerationService.validateEvaluatorTree]（EvaluatorTreeValidator + 绑定类型感知校验）；
 * - 条件树走 [ConditionTreeValidator.validateArgs]（内联树必须全绿；模板树按 D-007 语义 B 骨架契约，
 *   仅「条件/管道步骤/算子不存在」类结构性错误计失败，参数类报错降级为警告）。
 *
 * 运行方式（独立 JVM，勿与常规套件同跑——本测试会改 database.path）：
 * ```
 * mvn -pl configUi test -Dtest=TreeStaticAuditTest "-Daudit.db.path=<目标库文件>"
 * ```
 * 不传 -Daudit.db.path 时整测自动跳过（不影响常规套件）。
 * 目标库应是**副本**（如部署库 .bak 备份）：审计本身只读，但不直接碰生产库。
 */
class TreeStaticAuditTest {

    companion object {
        private val auditDbPath = System.getProperty("audit.db.path")?.takeIf { it.isNotBlank() }

        @BeforeClass
        @JvmStatic
        fun setup() {
            if (auditDbPath == null) return
            // 审计只读副本；用绝对路径消除对 user.dir 的隐性依赖（mvn -pl configUi 时 user.dir 未必是 configUi）
            val moduleRoot = Path.of("").toAbsolutePath()
            System.setProperty("hs_cards.db.path", moduleRoot.resolve("../hs_cards.db").normalize().toString())
            System.setProperty("database.path", auditDbPath)
            System.setProperty("cardgroup.dir.path", moduleRoot.resolve("../data/cardgroup").normalize().toString())
            loadMcpModules()
        }
    }

    @Test
    fun allStoredTreesPassStaticValidation() {
        assumeTrue("未指定 -Daudit.db.path，跳过静态审计", auditDbPath != null)
        val koin = GlobalContext.get()
        val aiService = koin.get<AiConfigGenerationService>()
        val treeConfigService = koin.get<TreeConfigService>()
        val conditionTreeService = koin.get<ConditionTreeConfigService>()
        val conditionTreeValidator = koin.get<ConditionTreeValidator>()

        val failures = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        // ── 评估树：保存侧同款校验 ──
        val trees = treeConfigService.loadAll()
        println(">>> 评估树 ${trees.size} 棵")
        trees.forEach { (entity, config) ->
            if (config == null) {
                failures += "评估树 ${entity.id}(${entity.name}) config_data 解析失败"
                return@forEach
            }
            val report = aiService.validateEvaluatorTree(
                SaveEvaluatorTreeRequest(
                    name = entity.name,
                    config = config,
                    description = entity.description,
                    existingId = entity.id,
                    enabled = entity.enabled,
                    managerId = entity.managerId
                )
            )
            if (!report.ok) {
                report.diagnostics.forEach {
                    failures += "评估树 ${entity.id}(${entity.name}) [${it.code}] ${it.message}"
                }
            } else {
                println("    ✓ ${entity.id} ${entity.name}")
            }
        }

        // ── 条件树：参数完整性校验 ──
        val conditions = conditionTreeService.loadAll()
        println(">>> 条件树 ${conditions.size} 棵")
        conditions.forEach { (entity, config) ->
            if (config == null) {
                failures += "条件树 ${entity.id}(${entity.name}) config_data 解析失败"
                return@forEach
            }
            val errors = conditionTreeValidator.validateArgs(config)
            if (errors.isEmpty()) {
                println("    ✓ ${entity.id} ${entity.name}")
                return@forEach
            }
            errors.forEach { error ->
                val structural = error.contains("不存在") || error.contains("不可用")
                when {
                    structural || entity.inlineCreated -> failures += "条件树 ${entity.id}(${entity.name}) $error"
                    else -> warnings += "条件树(模板) ${entity.id}(${entity.name}) $error"
                }
            }
        }

        warnings.forEach { println("    ⚠ $it") }
        assertTrue(
            "静态审计未通过：\n${failures.joinToString("\n")}",
            failures.isEmpty()
        )
        println(">>> 静态审计通过：评估树 ${trees.size} 棵 + 条件树 ${conditions.size} 棵全绿（警告 ${warnings.size} 条）")
    }
}
