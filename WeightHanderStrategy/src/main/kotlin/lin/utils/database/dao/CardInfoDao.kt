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

    /**
     * 查询卡牌初始费用（cards 表的静态 cost，非减费后的实时费用）。
     * 供法术费用兜底分使用（随从走实时身材、配置走等效费用，不查此值）。
     */
    fun queryCardCostById(cardId: String): Int? {
        val rowMapper = RowMapper { rs: ResultSet, _: Int -> rs.getInt("cost") }
        val sql = "SELECT cost FROM cards where cardId = ?"
        return DBConfig.CARD_DB.query(sql, rowMapper, cardId).firstOrNull()
    }

}