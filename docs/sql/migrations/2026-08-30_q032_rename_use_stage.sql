-- 2026-08-30 Q-032 数据库迁移脚本
-- 目标库: weightHandlerStrategy.db
-- 用途: UseStage 枚举改用时序中性命名，需同步存量 payload 中的阶段名字符串。
--
-- 背景: 阶段原命名（RESOURCE/CLEAR/DEFEND/COMBO/END）照搬用途语义，导致语义错位
--       （如「过牌牌」被放进名叫 CLEAR=解场的阶段）。现改为时序命名:
--         RESOURCE → FIRST
--         CLEAR    → MID
--         DEFEND   → LATE
--         COMBO    → (删除，零引用死阶段)
--         END      → LAST
--         SETUP / GENERAL 保持不变
--
-- ⚠️ 不迁移的后果: GroupUseOverride.stageOverride 是 UseStage 枚举，Jackson 未配置
--    READ_UNKNOWN_ENUM_VALUES_AS_NULL（仅配了 FAIL_ON_UNKNOWN_PROPERTIES=false），
--    遇到未知枚举值会抛 InvalidFormatException → **加载卡组配置直接崩溃**。
--
-- 用法: sqlite3 weightHandlerStrategy.db < 本文件
--      或手动在 sqlite3 中逐条执行。

-- 0. 迁移前检查（确认影响面）
--    SELECT binding_id, payload FROM card_group_behavior
--      WHERE behavior_type='OVERRIDE'
--        AND (payload LIKE '%RESOURCE%' OR payload LIKE '%"CLEAR"%'
--          OR payload LIKE '%"DEFEND"%' OR payload LIKE '%"COMBO"%' OR payload LIKE '%"END"%');

-- 1. card_group_behavior 的 OVERRIDE payload: stageOverride 字段
--    用 REPLACE 精确替换 JSON 字符串值（带引号，避免误伤条件名等其他字段）
UPDATE card_group_behavior
SET payload = REPLACE(payload, '"stageOverride":"RESOURCE"', '"stageOverride":"FIRST"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"stageOverride":"RESOURCE"%';

UPDATE card_group_behavior
SET payload = REPLACE(payload, '"stageOverride":"CLEAR"', '"stageOverride":"MID"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"stageOverride":"CLEAR"%';

UPDATE card_group_behavior
SET payload = REPLACE(payload, '"stageOverride":"DEFEND"', '"stageOverride":"LATE"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"stageOverride":"DEFEND"%';

-- END → LAST（注意: 无引号包裹的 END 可能出现在别处，故用带引号精确匹配）
UPDATE card_group_behavior
SET payload = REPLACE(payload, '"stageOverride":"END"', '"stageOverride":"LAST"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"stageOverride":"END"%';

-- COMBO 已删除且无存量映射目标（原为死阶段，零标签映射）。
-- 若存在（预期 0 行），需人工判断归属波段后再改，此处不做自动迁移。
--   查证（期望 0 行）:
--   SELECT binding_id, payload FROM card_group_behavior
--     WHERE behavior_type='OVERRIDE' AND payload LIKE '%"COMBO"%';

-- 2. conditionalStage 内的 stage / elseStage 字段（同样是 UseStage 枚举）
UPDATE card_group_behavior
SET payload = REPLACE(payload, '"stage":"RESOURCE"', '"stage":"FIRST"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"stage":"RESOURCE"%';

UPDATE card_group_behavior
SET payload = REPLACE(payload, '"stage":"CLEAR"', '"stage":"MID"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"stage":"CLEAR"%';

UPDATE card_group_behavior
SET payload = REPLACE(payload, '"stage":"DEFEND"', '"stage":"LATE"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"stage":"DEFEND"%';

UPDATE card_group_behavior
SET payload = REPLACE(payload, '"stage":"END"', '"stage":"LAST"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"stage":"END"%';

-- elseStage 字段名含 "stage" 后缀，前述 REPLACE 不会误伤（匹配的是 "stage":"X" 带前置引号）。
-- 但 "elseStage":"CLEAR" 不含 "stage":"CLEAR" 子串（前面是 eStage），故需单独处理:
UPDATE card_group_behavior
SET payload = REPLACE(payload, '"elseStage":"RESOURCE"', '"elseStage":"FIRST"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"elseStage":"RESOURCE"%';

UPDATE card_group_behavior
SET payload = REPLACE(payload, '"elseStage":"CLEAR"', '"elseStage":"MID"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"elseStage":"CLEAR"%';

UPDATE card_group_behavior
SET payload = REPLACE(payload, '"elseStage":"DEFEND"', '"elseStage":"LATE"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"elseStage":"DEFEND"%';

UPDATE card_group_behavior
SET payload = REPLACE(payload, '"elseStage":"END"', '"elseStage":"LAST"')
WHERE behavior_type = 'OVERRIDE'
  AND payload LIKE '%"elseStage":"END"%';

-- 3. 迁移后验证（期望全部 0 行）
--    SELECT binding_id, payload FROM card_group_behavior
--      WHERE behavior_type='OVERRIDE'
--        AND (payload LIKE '%"RESOURCE"%' OR payload LIKE '%"CLEAR"%'
--          OR payload LIKE '%"DEFEND"%' OR payload LIKE '%"COMBO"%' OR payload LIKE '%"END"%');
--
--    查看迁移结果:
--    SELECT binding_id, payload FROM card_group_behavior WHERE behavior_type='OVERRIDE';

-- 4. 说明: stage.order 配置（engine.properties）若已手工填写旧阶段名，
--    需同步改为新名，否则按「非法条目忽略 + 告警」处理（不崩溃，但配置静默失效）。
--    默认值是动态生成 UseStage.entries，无需迁移。
