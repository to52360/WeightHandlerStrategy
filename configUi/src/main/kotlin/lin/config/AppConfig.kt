package lin.config

import club.xiaojiawei.hsscriptcardsdk.config.DBConfig.CARD_DB_NAME
import lin.utils.database.TestDBUrl
import lin.utils.database.rootPath
import java.nio.file.Path

object AppConfig {
    val databasePath: Path
        get() = Path.of(rootPath, TestDBUrl)


    val defaultDirPath: Path =
        Path.of(rootPath, "../data/cardgroup")

    val hsCardsDbPath: Path
        get() = Path.of(rootPath, CARD_DB_NAME)
}
