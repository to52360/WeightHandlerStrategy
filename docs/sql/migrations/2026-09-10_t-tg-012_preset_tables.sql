-- 2026-09-10 T-TG-012 用途预设载体（按 Q-TG-003 定稿方案）
-- 背景：T-TG-009 曾按「分组骨架复制」建了 strategy_preset_binding —— 与用户模型（用途级兜底策略包）不符，
--       本脚本按定稿方案改造：
--         · 预设 = 覆盖层（时序）+ 排除层（树），配置侧解析（引擎零改动）；
--         · 解析链：卡组私有 > 预设 > 全局用途规则；
--         · 「不用预设」= card_group_manager.preset_id 为空（零开关）。
-- 形状（D-TG-003 手法：行存在 = 已声明）：
--   strategy_preset_excluded_tree  一行 = 该预设**排除**一棵评估树（黑名单；无用途依赖）
--   strategy_preset_purpose        一行 = 该预设对某用途的时序覆盖；覆盖列 NULL = 不覆盖该字段
-- ⚠️ 语义修订（2026-09-10，用户裁定）：树维度从「选择（白名单）」改为「**排除（黑名单）**」——
--   预设声明的是「哪些是不需要的项」，未排除的一律保留（更贴合「覆盖层」语义，录入量也更小）。
--   好处：无需「接管用途」机制，实现退化为一条 id 过滤。
-- 执行（两库都要跑，表结构对齐）：
--   sqlite3 weightHandlerStrategy.db ".read 2026-09-10_t-tg-012_preset_tables.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."

-- 白名单版旧表（空表，弃用）
DROP TABLE IF EXISTS strategy_preset_tree;

CREATE TABLE IF NOT EXISTS strategy_preset_excluded_tree
(
    preset_id TEXT NOT NULL,
    tree_id   TEXT NOT NULL,
    PRIMARY KEY (preset_id, tree_id)
);

CREATE TABLE IF NOT EXISTS strategy_preset_purpose
(
    preset_id                      TEXT NOT NULL,
    tag_id                         TEXT NOT NULL,
    default_stage                  TEXT,
    default_order_weight           REAL,
    default_surplus_idle_threshold INTEGER,
    default_replan_after_use       INTEGER,
    PRIMARY KEY (preset_id, tag_id)
);

-- 卡组引用预设（为空 = 不用预设）
-- ⚠️ 本脚本**非幂等**：重复执行时本行会报 `duplicate column name: preset_id`，属预期、忽略即可
-- （SQLite 无 `ADD COLUMN IF NOT EXISTS`；脚本按"一次性执行"设计，报错不影响后续验证段）。
ALTER TABLE card_group_manager
    ADD COLUMN preset_id TEXT;

-- T-TG-014：旧「分组骨架」表弃用（按错误模型所建，无数据可保留）
DROP TABLE IF EXISTS strategy_preset_binding;

-- ── 验证段（必跑）──
SELECT 'strategy_preset_excluded_tree 结构' AS check_item, name, type
FROM pragma_table_info('strategy_preset_excluded_tree');

SELECT 'strategy_preset_purpose 结构' AS check_item, name, type
FROM pragma_table_info('strategy_preset_purpose');

SELECT 'card_group_manager 的 preset_id 列' AS check_item, name, type
FROM pragma_table_info('card_group_manager')
WHERE name = 'preset_id';

-- 弃用表应均不存在（应为 0 行）
SELECT '弃用表残留（应为 0）' AS check_item, name
FROM sqlite_master
WHERE type = 'table'
  AND name IN ('strategy_preset_tree', 'strategy_preset_binding');
