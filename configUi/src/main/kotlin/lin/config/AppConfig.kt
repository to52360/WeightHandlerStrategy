package lin.config

import lin.utils.database.TestDBUrl
import lin.utils.database.rootPath
import java.nio.file.Path

object AppConfig {
    val databasePath: Path
        get() = Path.of(rootPath, TestDBUrl)


    val defaultDirPath: Path =
        Path.of(rootPath, "../data/cardgroup")
}
