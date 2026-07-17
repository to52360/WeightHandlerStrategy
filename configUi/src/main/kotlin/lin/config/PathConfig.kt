package lin.config

import club.xiaojiawei.hsscriptcardsdk.config.DBConfig.CARD_DB_NAME
import java.nio.file.Path

/** UI 层路径配置：加键只在本文件加一行。 */
object PathConfig {
    private val store = ConfigStore("app.properties")

    val databasePath: Path get() = store.path("database.path", "weightHandlerStrategy.db")
    val hsCardsDbPath: Path get() = store.path("hs_cards.db.path", CARD_DB_NAME)
    val defaultDirPath: Path get() = store.path("cardgroup.dir.path", "../data/cardgroup")
}
