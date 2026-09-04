-- 2026-09-03 数据修正脚本：修正 libram_paladin_test (f287be21) 的过期 description
-- 目标库: F:\myApp\HBuddy_2\plugin\WeightHandlerStrategy\weightHandlerStrategy.db
--          （源库无 f287be21，本脚本对源库无效、也无需执行）
--
-- 背景: manager description 停留在 2026-08-09 strategy_coverage 首测时的口径，
--       写着「缺口：libram_spells 组无策略、7张裸卡未覆盖，待补配」。
--       但该缺口已于 2026-08-10 收口（补评估树 5846c4f7 零费加分 + efe6dda0 神性圣契卡手惩罚），
--       libram_spells 从 UNCOVERED → COVERED，description 却没回头改，形成「虚假承诺」。
--       另：裸卡数 7 与实际不符——实测是 6 张（strategy_coverage 的 uncoveredCards 为准）。
--
-- ---------------------------------------------------------------------------
-- v2 修正（同日）：v1 文案里写的「卡组原始卡表已丢失、总数无法核对」**是错的，本脚本予以更正。
--       当时的判断依据是「data/cardgroup/ 下只有 verify_deck_1.cardgroup」就断定卡表丢失，
--       但**卡表一直都在**——它是 MCP 的 card_pool 资源，用
--         list(resource=card_pool)          → libram_paladin_test, cardCount=17
--         get(resource=card_pool, id=libram_paladin_test)  → 完整 17 张含中文名/费用/类型
--       即可取回。教训：**判定「数据丢失」前必须穷尽所有读取通道**，
--       只在项目源码目录里找不到 ≠ 数据没了（运行时数据可能在部署侧）。
--       v2 文案改为：17 张卡 / 6 张未入组，并把 6 张的 cardId 与中文名直接写进 description，
--       续接者不必再猜。
-- ---------------------------------------------------------------------------
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

-- 1. 修正 manager_description（幂等：WHERE 限定单卡组，重复执行结果一致；本文件已改过一次，重跑即为 v2）
--    注意列名是 manager_description（不是 description），状态列是 manager_status（不是 status）
UPDATE card_group_manager
SET manager_description = '圣契骑：减费引擎（奥尔多侍从+斩星巨刃）→ 圣契法术0费；莱妮莎光环双放；星际研究员定向检索；决战+棱彩光束combo。现状（2026-09-03 核对，卡共 17 张）：六组均已配策略（libram_spells 的 0 费加分树与 GDB_138 卡手惩罚树已于 2026-08-10 补齐），AuraBoost 5401d257 已落库。遗留：6 张未入组裸卡——光鳐 TID_077 / 纳迦侍从 TID_098 / 寒刃勇士 ICC_820 / 冻感舞步 JAM_006 / 责难 GIL_203 / 适者生存 UNG_961；英文组名是历史遗留，新配置按新 SOP 用中文命名。'
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
