-- 2026-09-18 D-DP-001 全局光环行归入预设白名单（Q-DP-002「光环入预设」）
--
-- 背景 / 语义：全局 aura_boost 行（manager_id IS NULL）从「对所有启用卡组**隐式**生效」改为**候选池** ——
--   预设经 AURA_BOOST 维度声明**白名单**（本脚本写入 `{"auraIds":[…]}`），卡组引用该预设即得；
--   **未引用预设 ⇒ 无全局光环**（与 D-TG-018「不引用预设 = 合法终态、无隐式作用」同向）。
-- 零 DDL：复用 strategy_dimension_item 单表，`purpose_tag` 列用哨兵 `_ALL_`（光环不按用途分组）。
-- 行为零变化：迁移后原全局行仍生效（只是改为经白名单纳入）；⚠️ 需**重启引擎**才生效（装配期一次性解析）。
-- 前置：目标预设必须存在（默认归入部署库的 6559d821「通用预设」）。若目标预设不同，请先改本脚本的 owner_id。
-- 幂等：主键 (scope, owner_id, dimension, purpose_tag) + INSERT OR IGNORE ⇒ 重复执行安全。
--
-- 执行（两库都要跑，表结构对齐 / 数据不同步）：
--   sqlite3 weightHandlerStrategy.db ".read docs/sql/migrations/2026-09-18_dp-002_aura_into_preset.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."
-- （源库全局行为 0 ⇒ SELECT 无行 ⇒ 本脚本在源库不写入任何行，属预期。）

-- ⚠️ `HAVING COUNT(*) > 0` 不可省：SQLite 的聚合查询（`json_group_array`）**无 GROUP BY 时恒返回一行**，
-- 没有它会在「全局光环行为 0」的库（如源库）插入一行 `{"auraIds":[]}` 的孤儿维度项（目标预设不存在）。
INSERT OR IGNORE INTO strategy_dimension_item (scope, owner_id, dimension, purpose_tag, payload)
SELECT 'PRESET', '6559d821', 'AURA_BOOST', '_ALL_',
       json_object('auraIds', json_group_array(id))
FROM aura_boost
WHERE manager_id IS NULL
HAVING COUNT(*) > 0;

-- ── 验证段（必跑）──
SELECT '白名单行（应 1 行 / 部署库）' AS check_item, scope, owner_id, dimension, purpose_tag, payload
FROM strategy_dimension_item
WHERE dimension = 'AURA_BOOST';

SELECT '全局光环候选数' AS check_item, COUNT(*) AS cnt
FROM aura_boost
WHERE manager_id IS NULL;

SELECT '预设清单' AS check_item, id, name
FROM strategy_preset;
