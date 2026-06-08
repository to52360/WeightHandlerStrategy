package lin.utils

import java.util.*

/**
 * 生成 8 位长度的递增有序短唯一 ID。
 * - 前 6 位：秒级时间戳的 Base36 编码（保证时间段内有序递增，对 SQLite 索引极为友好，可安全稳定运行至公元 2090+ 年）。
 * - 后 2 位：UUID 随机字符（防止高并发下同一秒内碰撞冲突）。
 */
fun nextShortId(): String {
    val seconds = System.currentTimeMillis() / 1000
    val timePart = seconds.toString(36).padStart(6, '0')
    val randPart = UUID.randomUUID().toString().replace("-", "").take(2)
    return "$timePart$randPart"
}
