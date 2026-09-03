-- 2026-09-03 数据修正脚本：修正 libram_paladin_test (f287be21) 的过期 description
-- 目标库: F:\myApp\HBuddy_2\plugin\WeightHandlerStrategy\weightHandlerStrategy.db
--          （源库无 f287be21，本脚本对源库无效、也无需执行）
--
-- 背景: manager description 停留在 2026-08-09 strategy_coverage 首测时的口径，
--       写着「缺口：libram_spells 组无策略、7张裸卡未覆盖，待补配」。
--       但该缺口已于 2026-08-10 收口（补评估树 5846c4f7 零费加分 + efe6dda0 神性圣契卡手惩罚），
--       libram_spells 从 UNCOVERED → COVERED，description 却没回头改，形成「虚假承诺」。
--       另：裸卡数 7 与实际不符——按现存六组 cardIds 反推是 6 张，
--       且卡组原始卡表已丢失（data/cardgroup/ 下只剩 verify_deck_1.cardgroup，
--       card_purpose 表无 manager_id 列），总数无从核对，故新文案不再断言总数。
--
--       本脚本只改文案，不动任何策略数据。
--
-- ⚠️ 执行前必须备份（2026-09-02 已有 T-123 落盘数据全库丢失且无快照的前车之鉴）：
--    copy /Y weightHandlerStrategy.db weightHandlerStrategy.db.bak-<日期>
--
-- 用法: sqlite3 weightHandlerStrategy.db ".read docs/sql/migrations/本文件"
--      （Windows 下不要用 Get-Content | sqlite3 管道，含 -- 注释的脚本会被静默丢末尾语句）

-- 0. 迁移前检查（确认目标行存在且确为待修文案，期望 1 行且 manager_description 含「7张裸卡」）
--    SELECT id, name, manager_status, manager_description FROM card_group_manager WHERE id = 'f287be21';

-- 1. 修正 manager_description（幂等：WHERE 限定单卡组，重复执行结果一致）
--    注意列名是 manager_description（不是 description），状态列是 manager_status（不是 status）
UPDATE card_group_manager
SET manager_description = '圣契骑：减费引擎（奥尔多侍从+斩星巨刃）→ 圣契法术0费；莱妮莎光环双放；星际研究员定向检索；决战+棱彩光束combo。现状（2026-09-03 核对）：六组均已配策略（libram_spells 的 0 费加分树与 GDB_138 卡手惩罚树已于 2026-08-10 补齐），AuraBoost 5401d257 已落库。遗留：按现有六组 cardIds 反推有 6 张卡未入组（光鳐/纳迦侍从/寒刃勇士/冻感舞步/责难/适者生存），卡组原始卡表已丢失、总数无法核对；英文组名是历史遗留，新配置按新 SOP 用中文命名。'
WHERE id = 'f287be21';

-- 2. 迁移后验证

--    2.1 文案已更新且未误伤其它卡组（期望 1 行，manager_description 含「六组均已配策略」）
--    SELECT id, name, manager_description FROM card_group_manager WHERE id = 'f287be21';

--    2.2 受影响行数（期望 1）
--    SELECT changes();

--    2.3 策略数据零改动（期望 6 / 6 / 1 / 1 / 3）
--    SELECT (SELECT COUNT(*) FROM card_group_binding WHERE manager_id = 'f287be21') AS groups,
--           (SELECT COUNT(*) FROM tree_config)                                       AS trees,
--           (SELECT COUNT(*) FROM combo_plan_definition WHERE manager_id = 'f287be21') AS combos,
--           (SELECT COUNT(*) FROM aura_boost WHERE manager_id = 'f287be21')         AS auras,
--           (SELECT COUNT(*) FROM condition_tree_config)                            AS cond_trees;

--    2.4 整库完整性
--    PRAGMA integrity_check;
