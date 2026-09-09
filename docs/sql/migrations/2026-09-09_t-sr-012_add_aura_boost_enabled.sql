-- aura_boost 增加启用开关列（enabled）
-- 背景：sop-rework T-SR-012（源自 open-questions Q-OQ-002）——AuraBoost 无 `enabled` 列，
--       停用它只能 `delete`（虽有 delete_snapshot 可恢复），缺"留在库里但临时不生效"的通道。
-- 语义：enabled=1 进引擎（默认）；enabled=0 仍在库中可见（`list`/`get` 会显示），但不进引擎
--      ——过滤点 `SqliteAuraBoostConfigProvider.findAll()`（与既有"全局 ∪ 已启用卡组"过滤叠加）。
-- ⚠️ 能力边界（见 open-questions Q-OQ-001/Q-OQ-005）：本列是**行级**开关，只能"临时停用这条规则"；
--      做不到"按卡组启停"（引擎侧按"所有已启用卡组"加载，无"当前卡组"概念，靠单活卡组约束规避）。
-- 兼容：NOT NULL DEFAULT 1 —— 存量行自动为启用，行为与迁移前完全一致。
-- 执行（两库都要跑，表结构对齐）：
--   sqlite3 weightHandlerStrategy.db ".read 2026-09-09_t-sr-012_add_aura_boost_enabled.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."

ALTER TABLE aura_boost
    ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1;

-- ── 验证段（必跑，ALTER 曾静默不生效）──
SELECT 'enabled 列定义' AS check_item,
       name,
       type,
       "notnull"        AS not_null,
       dflt_value       AS default_value
FROM pragma_table_info('aura_boost')
WHERE name = 'enabled';

-- 存量数据应全部为 1（新增列默认值）；非 1 说明写入侧有问题
SELECT '非启用行数（应为 0）' AS check_item, COUNT(*) AS cnt
FROM aura_boost
WHERE enabled <> 1;
