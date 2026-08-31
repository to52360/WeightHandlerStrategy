-- 2026-08-31 T-001 数据库迁移脚本：分组成员资格（谓词组）
-- 目标库: weightHandlerStrategy.db
-- 用途: 为「条件定义成员的分组」加列。
--
-- 背景: 分组原来只能靠显式卡列表（card_ids）定义成员。T-001 引入谓词组——
--       用条件树定义成员（如「所有法术」），运行期逐卡判定，不用穷举 cardId。
--       成员资格领域层建模为密封类 GroupMembership：
--         STATIC    → 显式卡列表（原行为，存 card_ids）
--         PREDICATE → 条件定义成员（存 condition_id，运行期求值）
--
-- 新增列:
--   card_group_binding.member_type      TEXT    NOT NULL DEFAULT 'STATIC'  -- 成员资格类型
--   card_group_binding.condition_id     TEXT                               -- PREDICATE 专用：条件树 id
--   card_group_binding.include_derived  INTEGER                            -- PREDICATE 专用：是否纳入卡池外的卡；NULL=回落卡组级
--   card_group_manager.default_include_derived INTEGER                     -- 卡组级默认；NULL=回落 false
--
-- 覆盖链（运行期解析）:
--   Predicate.includeDerived > CardGroupManagerConfig.defaultIncludeDerived > 内建兜底 false
--
-- ⚠️ 不迁移的后果: 查询 SELECT 新列会报 "no such column" → 卡组加载失败。
--
-- 用法: sqlite3 weightHandlerStrategy.db < 本文件
--      或手动在 sqlite3 中逐条执行。

-- 0. 迁移前检查（确认哪些列已存在，避免重复加列报错）
--    PRAGMA table_info(card_group_binding);
--    PRAGMA table_info(card_group_manager);

-- 1. card_group_binding：成员资格类型 + 谓词参数
ALTER TABLE card_group_binding
    ADD COLUMN member_type TEXT NOT NULL DEFAULT 'STATIC';
ALTER TABLE card_group_binding
    ADD COLUMN condition_id TEXT;
ALTER TABLE card_group_binding
    ADD COLUMN include_derived INTEGER;

-- 2. card_group_manager：卡组级「是否纳入卡池外卡」默认值
ALTER TABLE card_group_manager
    ADD COLUMN default_include_derived INTEGER;

-- 3. 迁移后验证
--    3.1 列已就位（期望各 1 行）
--    SELECT name FROM PRAGMA_table_info('card_group_binding')
--      WHERE name IN ('member_type','condition_id','include_derived');
--    SELECT name FROM PRAGMA_table_info('card_group_manager')
--      WHERE name = 'default_include_derived';
--
--    3.2 存量分组应全部是 STATIC（期望 0 行异常）
--    SELECT id, name, member_type FROM card_group_binding
--      WHERE member_type IS NULL OR member_type NOT IN ('STATIC','PREDICATE');

-- 4. 说明
--    存量数据无需改写：member_type 默认 'STATIC'，既有分组行为完全不变；
--    condition_id / include_derived / default_include_derived 对静态组无意义，保持 NULL。
--    ⚠️ SQLite 的布尔列用 INTEGER 存储（0/1），NULL 表示"未声明"——
--       与 0（显式 false）语义不同，读取端用 getInt + wasNull 区分，切勿改动存量 NULL。
