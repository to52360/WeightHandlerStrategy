package lin.script

import lin.bean.ComboCard
import lin.domain.MyWarManage

import lin.serviceLoader.weightRule.WeightCondition
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.time.Instant
import javax.script.ScriptEngine
import javax.script.ScriptEngineManager
import javax.script.ScriptException
import kotlin.concurrent.thread


class WeightConditionEngine(private val scriptDir: Path) {
    private val scriptEngine: ScriptEngine by lazy {
        ScriptEngineManager().getEngineByExtension("kts") ?:
        throw IllegalStateException("Kotlin script engine not available")
    }

    private val strategies = mutableMapOf<String, WeightCondition>()
    private var lastReload = Instant.now()

    init {
        reloadStrategies()
        setupFileWatcher()
    }

    private fun reloadStrategies() {
        val newStrategies = mutableMapOf<String, WeightCondition>()

        Files.list(scriptDir)
            .filter { it.toString().endsWith(".cardstrategy.kts") }
            .forEach { scriptFile ->
                try {
                    val strategy = loadStrategy(scriptFile)
                    newStrategies[strategy.id()] = strategy
                } catch (e: Exception) {
                    System.err.println("Failed to load ${scriptFile.fileName}: ${e.message}")
                }
            }

        strategies.clear()
        strategies.putAll(newStrategies)
        lastReload = Instant.now()
    }

    private fun loadStrategy(scriptFile: Path): WeightCondition {
        val script = Files.readString(scriptFile)

        // 创建绑定上下文
        val bindings = scriptEngine.createBindings()

        // 执行脚本
        return scriptEngine.eval(script, bindings) as? WeightCondition
            ?: throw ScriptException("Script did not return WeightCondition")
    }

    private fun setupFileWatcher() {
        val watchService = FileSystems.getDefault().newWatchService()
        scriptDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY)

        thread(isDaemon = true) {
            while (true) {
                val key = watchService.take()
                key.pollEvents().forEach {
                    if (it.kind() == StandardWatchEventKinds.ENTRY_MODIFY) {
                        val fileName = it.context() as Path
                        if (fileName.toString().endsWith(".cardstrategy.kts")) {
                            // 避免频繁重载
                            if (Instant.now().isAfter(lastReload.plusSeconds(2))) {
                                System.err.println("Reloading strategies due to change in $fileName")
                                reloadStrategies()
                            }
                        }
                    }
                }
                key.reset()
            }
        }
    }

    /**
     * todo-future
     */
    fun calculateCardWeights(cards: List<ComboCard>, myWarManage: MyWarManage): Map<ComboCard, Int> {
        TODO()
        /*return cards.associateWith { card ->
            strategies.values.sumOf { it.calculateWeight(card, war) }
        }*/
    }

    fun selectBestCard(cards: List<ComboCard>, myWarManage: MyWarManage): ComboCard? {
        return cards.maxByOrNull { calculateCardWeights(listOf(it), myWarManage)[it] ?: 0 }
    }
}

