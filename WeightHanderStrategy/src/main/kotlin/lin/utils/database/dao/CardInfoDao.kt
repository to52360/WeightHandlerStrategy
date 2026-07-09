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

    /** 按 cardId 查单卡静态费用，用于按需解析基础分（首次出现时查一次并缓存，见 scoring-model/TRACKER.md） */
    fun queryCardCostById(cardId: String): Int? {
        val rowMapper = RowMapper { rs: ResultSet, _: Int -> rs.getInt("cost") }
        val sql = "SELECT cost FROM cards where cardId = ?"
        return DBConfig.CARD_DB.query(sql, rowMapper, cardId).firstOrNull()
    }
}