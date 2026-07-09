package lin.utils

import lin.dao.CardGroupJsonParser
import lin.db.CardIdNameText
import lin.db.HsCardRepository
import java.nio.file.Path
import java.util.*

/**
 * 炉石卡组代码（deck string，如 `AAEBAZfDAwaopAOX7wS2xAXH...`）解析工具。
 *
 * 卡组代码为 base64 编码的 VarInt 流，遵循 HearthSim/hearthstone-deckstrings 规范：
 *   [0x00] [version=1] [format varint] [heroes: count + ids] [cards: 3 组循环(count=1/2/n)]
 * 其中卡牌标识为 Hearthstone 的 **dbfId**（对应 [hs.cards] 表的 `dbfId` 列，并非自增主键 `id`）。
 */
object HearthstoneDeckCodeParser {

    /** 解码后的单张卡：dbfId + 数量 */
    data class DeckCodeCard(val dbfId: Int, val count: Int)

    /** 解码后的整体结构 */
    data class HearthstoneDeckCode(
        val format: Int,
        val heroes: List<Int>,
        val cards: List<DeckCodeCard>
    )

    /**
     * 纯解码：将卡组代码解析为 [HearthstoneDeckCode]，不查库。
     * 返回的 [HearthstoneDeckCode.cards] 按 (数量1组, 数量2组, 数量n组) 顺序排列。
     */
    fun decode(code: String): HearthstoneDeckCode {
        val bytes = decodeBase64(code)
        var pos = 0

        require(bytes[pos] == 0.toByte()) { "非法的炉石卡组代码：首字节不为 0" }
        pos++

        val version = bytes[pos].toInt() and 0xFF
        require(version == 1) { "不支持的卡组代码版本: $version" }
        pos++

        val (format, p1) = readVarInt(bytes, pos); pos = p1

        val (heroCount, p2) = readVarInt(bytes, pos); pos = p2
        val heroes = mutableListOf<Int>()
        repeat(heroCount.toInt()) {
            val (hero, p) = readVarInt(bytes, pos); pos = p
            heroes.add(hero.toInt())
        }

        val cards = mutableListOf<DeckCodeCard>()
        for (i in 1..3) {
            val (groupCount, p3) = readVarInt(bytes, pos); pos = p3
            repeat(groupCount.toInt()) {
                val (dbfId, p4) = readVarInt(bytes, pos); pos = p4
                val count = if (i <= 2) {
                    i
                } else {
                    val (cnt, p5) = readVarInt(bytes, pos); pos = p5
                    cnt.toInt()
                }
                cards.add(DeckCodeCard(dbfId.toInt(), count))
            }
        }

        return HearthstoneDeckCode(format.toInt(), heroes, cards)
    }

    /**
     * 解码卡组代码并通过 [HsCardRepository] 查询卡牌元数据，返回 [CardIdNameText] 列表。
     * 按卡组首次出现顺序去重（同一 dbfId 只出现一次，数量信息被丢弃）。
     * 本地 [hs.cards] 中不存在的卡（缺卡）不会出现在结果里。
     */
    fun parseToCards(code: String, repository: HsCardRepository): List<CardIdNameText> {
        val deck = decode(code)
        val orderedDbfIds = deck.cards.map { it.dbfId }.distinct()
        val byDbfId = repository.findCardMapByDbfIds(orderedDbfIds)
        return orderedDbfIds.mapNotNull { byDbfId[it] }
    }

    /**
     * 端到端：解码卡组代码 → 查库得到 [CardIdNameText] → 写成 `.cardgroup` JSON 文件。
     * 文件写入 [CardGroupJsonParser.saveCardGroup] 默认目录（app.properties 的 cardgroup.dir.path），
     * 文件名为 `$groupName.cardgroup`。返回写入的文件路径。
     */
    fun parseDeckCodeToCardGroupFile(
        code: String,
        repository: HsCardRepository,
        groupName: String,
        enabled: Boolean = true
    ): Path {
        val cards = parseToCards(code, repository)
        return CardGroupJsonParser.saveCardGroup(cards, groupName, enabled)
    }

    private fun decodeBase64(code: String): ByteArray {
        val normalized = if (code.length % 4 != 0) code + "=".repeat(4 - code.length % 4) else code
        return Base64.getDecoder().decode(normalized)
    }

    private fun readVarInt(data: ByteArray, pos: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var p = pos
        var b: Int
        do {
            b = data[p].toInt() and 0xFF
            p++
            value = value or ((b and 0x7F).toLong() shl shift)
            shift += 7
        } while ((b and 0x80) != 0)
        return value to p
    }
}
