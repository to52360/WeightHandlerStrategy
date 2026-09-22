package lin.utils.serviceLoader

import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * 引擎扩展 jar 的目录解析与装载。
 *
 * ## 落点：本引擎的**专属资产目录**（与部署库同目录）
 *
 * ```
 * <引擎 jar 所在目录>/weightHandlerStrategy/     ← 扩展 jar 与 weightHandlerStrategy.db 同居
 * ```
 *
 * 该目录语义即「本引擎的资产」——**放进去的扩展 jar 就是本引擎的扩展**，不靠任何命名约定。
 * 不再扫描 `<引擎 jar 所在目录>` 本身：那通常是宿主插件目录，混放其它插件时无法凭文件名区分。
 *
 * 解析基准取**引擎 jar 自身所在目录**而非 `user.dir`：扩展落点不该随进程 cwd 漂移
 * （历史上以 `user.dir` 为基准，换个启动目录扩展就静默失联，且失败只在上层表现为 Koin 的
 * `NoDefinitionFoundException`，完全看不出与「jar 放错目录」有关）。
 *
 * ## 两条过滤规则（都不涉及包名 / 类名前缀）
 *
 * - **排除引擎自身 jar**：否则同一份服务声明会在父加载器与子加载器各被发现一次，服务被重复注册。
 * - **只收「声明了 SPI 服务文件」的 jar**（`META-INF/services/` 下任意服务文件）：
 *   挡住误放的非扩展 jar。**服务接口属于哪个包不参与判定**——扩展作者可自由命名，无需碰 `lin.` 前缀。
 */
class JarClassLoader(
    private val candidateDirs: List<Path> = defaultCandidateDirs(),
    private val parent: ClassLoader = Thread.currentThread().contextClassLoader,
) {

    /** 命中结果：单次扫描，装载与诊断共用（避免二次扫描出现不一致）。 */
    private val hitJars: List<Path> = resolve()

    /** 无扩展 jar 时返回 null（调用方回落自身 classloader）。 */
    fun classLoader(): ClassLoader? {
        if (hitJars.isEmpty()) return null
        val urls = ArrayList<URL>()
        for (jar in hitJars) {
            urls.add(jar.toUri().toURL())
        }
        return URLClassLoader(urls.toTypedArray(), parent)
    }

    /** 诊断文本：逐个候选目录列出命中 jar + 实际装载清单——故障时据此点名「扩展该放哪」。 */
    fun diagnose(): String {
        val scanned = candidateDirs.joinToString(" ; ") { dir ->
            val names = jarsIn(dir).joinToString(",") { it.fileName.toString() }.ifEmpty { "无" }
            "$dir -> $names"
        }
        val loaded = hitJars.joinToString(",") { it.fileName.toString() }.ifEmpty { "无" }
        return "扩展 jar 扫描[$scanned] 实际装载[$loaded]"
    }

    /** 合并各候选目录命中的 jar，同名只取第一个。 */
    private fun resolve(): List<Path> {
        val hit = ArrayList<Path>()
        for (dir in candidateDirs) {
            for (jar in jarsIn(dir)) {
                if (hit.none { it.fileName == jar.fileName }) hit.add(jar)
            }
        }
        return hit
    }

    /** 目录下的合法扩展 jar（缺目录 / 非目录 → 空表）。 */
    private fun jarsIn(dir: Path): List<Path> {
        val files: Array<File> = dir.toFile().listFiles() ?: return ArrayList()
        val self: Path? = engineJar
        val result = ArrayList<Path>()
        for (file in files) {
            if (!file.isFile || !file.name.endsWith(".jar")) continue
            val jar = file.toPath()
            if (self != null && jar.toAbsolutePath().normalize() == self) continue
            if (!declaresSpi(jar)) continue
            result.add(jar)
        }
        result.sortBy { it.fileName.toString() }
        return result
    }

    private companion object {
        const val EXT_DIR_NAME = "weightHandlerStrategy"
        const val SERVICE_DIR = "META-INF/services/"

        /** 引擎自身 jar（开发期直接跑 classes 时为目录，此时自身排除自然失效）。 */
        val engineJar: Path? = runCatching {
            JarClassLoader::class.java.protectionDomain?.codeSource?.location?.toURI()?.let { Path.of(it) }
        }.getOrNull()?.toAbsolutePath()?.normalize()

        /** 候选目录：以「引擎 jar 所在目录」为基准，旧 `user.dir` 路径兜底（同一专属目录的两种基准）。 */
        fun defaultCandidateDirs(): List<Path> {
            val dirs = ArrayList<Path>()
            val engineDir = engineJar?.parent
            if (engineDir != null) dirs.add(engineDir.resolve(EXT_DIR_NAME))
            val userDir = System.getProperty("user.dir")
            if (userDir != null) dirs.add(Path.of(userDir, "plugin", EXT_DIR_NAME))
            return dirs.distinct()
        }

        /** 该 jar 是否声明了 SPI 服务（`META-INF/services/` 下存在服务文件）；**不限定接口包名**。 */
        fun declaresSpi(jar: Path): Boolean = runCatching {
            JarFile(jar.toFile()).use { jf ->
                val entries = jf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.startsWith(SERVICE_DIR) && !entry.isDirectory) return@use true
                }
                false
            }
        }.getOrDefault(false)
    }
}
