package lin.utils.database.dao

import club.xiaojiawei.hsscriptcardsdk.config.DBConfig

import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet

data class CardRace(val race: String, val races: String)
class CardInfoDao {

    fun queryCardRaceById(cardId: String): CardRace? {
        val rowMapper = RowMapper { rs: ResultSet, _: Int ->
            CardRace(
                race = rs.getString("race"),
                races = rs.getString("races")
            )
        }
        val sql = "SELECT race,races FROM cards where cardId = ?"
        return DBConfig.CARD_DB.query(sql, rowMapper, cardId).firstOrNull()
    }

}