package lin.daoTest

import lin.dao.CardGroupJsonParser
import org.junit.jupiter.api.Test
import java.nio.file.Path

class CardSelectOptionProviderTest {
    @Test
    fun test() {
        val parser = CardGroupJsonParser.loadAllCardGroups()
        println(parser)
    }

    fun defaultDirPath(): Path =
        Path.of(System.getProperty("user.dir"), "../../data/cardgroup")
}
