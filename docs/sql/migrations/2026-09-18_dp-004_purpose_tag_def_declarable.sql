-- 2026-09-18 D-DP-004 标记定义新增「可声明作用」列（Q-DP-001 晋级制）
--
-- 背景：declarable-purpose 主题 Q-DP-001 —— 「标记获得行为」的通道从代码常量（5 个内置作用）
--       泛化为可配置机制：自定义标记经晋级（declarable = 1）即可被预设 / 卡组增量项声明时序与惜售。
-- 语义（D-DP-004）：
--   declarable = 1：该**自定义**标记可被声明（候选查询口径 = builtin = 0 AND declarable = 1）；
--   declarable 对 builtin = 1 的行是**忽略域** —— 内置 7 个的可声明性由代码常量决定
--   （`PurposeTagRuleRepository.DECLARABLE_PURPOSES` 的 5 个；FINISH / EXTRA_COST 恒不可声明，
--    见 Q-033 / T-TG-031），写侧忽略入参、UI 禁用、候选查询带 builtin = 0。
-- 回填策略：统一默认 0，**不为内置行回填**（内置侧读常量，回填只会造成"库里看着生效其实不读"的误导）。
-- 兼容：本脚本**只执行一次**（SQLite 重复 ADD COLUMN 会报 duplicate column name）。
--
-- 执行（两库都要跑，表结构对齐 / 数据不同步）：
--   sqlite3 weightHandlerStrategy.db ".read docs/sql/migrations/2026-09-18_dp-004_purpose_tag_def_declarable.sql"
--   sqlite3 F:/myApp/HBuddy_2/plugin/WeightHandlerStrategy/weightHandlerStrategy.db ".read ..."

ALTER TABLE purpose_tag_def ADD COLUMN declarable INTEGER NOT NULL DEFAULT 0;

-- ── 验证段（必跑，ALTER 曾静默不生效）──
SELECT '表结构' AS check_item, name, type, "notnull" AS not_null, dflt_value AS default_value
FROM pragma_table_info('purpose_tag_def');

SELECT '可声明列取值分布（存量应全部为 0）' AS check_item, declarable, COUNT(*) AS cnt
FROM purpose_tag_def
GROUP BY declarable;

SELECT '内置行 declarable 非 0（应为 0 / 忽略域）' AS check_item, COUNT(*) AS cnt
FROM purpose_tag_def
WHERE builtin = 1 AND declarable <> 0;
