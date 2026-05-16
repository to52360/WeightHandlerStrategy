package lin.config

import lin.utils.database.DefDBUrl
import java.nio.file.Path

object AppConfig {
    /** 数据库文件位置，支持通过系统属性 `-Dapp.db.path=xxx` 覆盖 */
    val databasePath: Path
        get() = DefDBUrl
}
