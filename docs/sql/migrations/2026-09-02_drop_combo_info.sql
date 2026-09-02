-- 2026-09-02 数据库迁移脚本：删除旧体系 combo_info 表
-- 目标库: weightHandlerStrategy.db
--
-- 用途: 旧 combo 编排体系已整体拆除，表随之退役。
--
-- 背景: 旧体系用 combo_info 一张表承载全部 combo 语义，且把三种不同的语义
--       塞进同一个 combo_weight 字段（README 原话「comboWeight 多种语义，对扩展和维护有麻烦」）：
--         ① 换牌正负（负数=互斥）
--         ② 数值大小（出牌顺序）
--         ③ 同组加权
--       combo_type 是字符串，运行期按 Koin named 派发到 ComboParse 实现
--       （def/before/change/first/last）。
--
--       新体系 combo_plan_definition + ComboPlanDefinition 已把这些语义拆成正交字段：
--         bind_id            → coreGroupIds
--         dep_ids            → depGroupIds
--         combo_weight ①②③  → score / coreMutex / relation（SCORE_ONLY|CORE_BEFORE_DEP|DEP_BEFORE_CORE）
--         combo_type 字符串   → relation 枚举（编译期穷举）
--       代码侧 ComboParse 接口早在 T-002 就标注为「旧排序通道弃用，仅兼容旧 DB 数据解析」。
--
-- 前置条件（必须已完成的拆链，否则删表会让引擎初始化崩溃）:
--   旧链路为 ComboDomain.init -> CardConfigBind -> ParseCombo.parse() -> ComboInfoDao.findAll()
--   -> SELECT * FROM combo_info。因此**必须先删代码再删表**，顺序不能颠倒。
--   本次同步拆除：ParseCombo / ComboInfoDao / ComboInfo(含 @Deprecated 的 Combo) /
--   lin.domain.combo 整包，以及 DataModule、DomainModule 中的注册。
--
-- 为什么删表安全（拆链之后）:
--   · 表本身 0 行
--   · 全库零写入方（无任何 INSERT/UPDATE/DELETE，也无 UI/MCP/Repository 写它）
--   → 旧链路运行期本就恒为空转，删表不改变任何行为。
--
-- ⚠️ 部署库说明: 部署库（F:\myApp\HBuddy_2\plugin\WeightHandlerStrategy）从来就没有这张表，
--    故本脚本用 IF EXISTS 保证幂等，部署库执行后无变化。
--
-- 用法: sqlite3 weightHandlerStrategy.db < 本文件
--      或手动在 sqlite3 中逐条执行。

-- 0. 迁移前检查（确认影响面，期望 total = 0）
--    SELECT COUNT(*) AS total FROM combo_info;
--    SELECT name FROM sqlite_master WHERE type='table' AND name='combo_info';

-- 1. 删表（幂等：表不存在时不报错）
DROP TABLE IF EXISTS combo_info;

-- 2. 迁移后验证

--    2.1 表已不存在（期望 0 行）
--    SELECT name FROM sqlite_master WHERE type='table' AND name='combo_info';

--    2.2 新体系载体仍在（期望 combo_plan_definition 存在且有数据）
--    SELECT name FROM sqlite_master WHERE type='table' AND name='combo_plan_definition';
--    SELECT COUNT(*) FROM combo_plan_definition;

--    2.3 整库完整性
--    PRAGMA integrity_check;
