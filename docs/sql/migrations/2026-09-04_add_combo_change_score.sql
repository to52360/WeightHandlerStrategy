-- combo_plan_definition 增加「起手组合协同加分」列（change_score）
-- 背景：起手换牌只认单卡 changeWeight，无法表达「A、B 单留一般、一起留才值钱」（Q-009 / changeScore）。
-- 语义：本 combo 的核心组与依赖组在起手同时保留时，给保留子集额外加一次该分值；
--       默认 0 = 不加成；只被起手换牌消费，不影响出牌评分（出牌协同加分是 score 列）。
-- 兼容：NOT NULL DEFAULT 0 —— 存量行自动为 0，行为与迁移前完全一致。
-- 执行：sqlite3 <db> ".read 2026-09-04_add_combo_change_score.sql"

ALTER TABLE combo_plan_definition
    ADD COLUMN change_score REAL NOT NULL DEFAULT 0;

-- ── 验证段（必跑，ALTER 曾静默不生效）──
SELECT 'change_score 列定义' AS check_item,
       name,
       type,
       "notnull"             AS not_null,
       dflt_value            AS default_value
FROM pragma_table_info('combo_plan_definition')
WHERE name = 'change_score';

-- 存量数据应全部为 0（新增列默认值），非空值说明写入侧有问题
SELECT '非 0 行数（应为 0）' AS check_item, COUNT(*) AS cnt
FROM combo_plan_definition
WHERE change_score <> 0;
