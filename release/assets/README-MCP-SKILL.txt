============================================================
 Deck-Plugin-Market —— MCP 配置示例 + AI 配置生成 SOP
============================================================

一、包内容
  mcp.json                                 MCP 客户端配置示例（路径已按打包时的宿主目录填好）
  skills\use-ai-config-generator\          「让 AI 配置卡组策略」的标准作业流程（SOP）技能
       SKILL.md                            分阶段流程 + 硬性门禁（主干）
       references\*.md                     分组 / combo / 评估树 / 评分模型 / 实战案例（按需加载）
  README-MCP-SKILL.txt                     本文件

  前提：本包只装「AI 怎么用」的部分，插件本体在另一个压缩包（plugin 包）里，请先按它的
  README-INSTALL.txt 解压到宿主根目录，否则 MCP 工具连不上。

二、配置 MCP 客户端
  1. 把 mcp.json 的内容合并进 AI 客户端的 MCP 配置文件：
        CodeBuddy      <工作区>\.codebuddy\mcp.json
        Claude Code    <工作区>\.mcp.json
        Cursor         <工作区>\.cursor\mcp.json
     ⚠️ args 里的 <PLUGIN_DIR> 是占位符（打包时没填 HOST_ROOT 就会出现它），替换成插件的
        资产目录，即 <宿主根目录>\plugin\WeightHandlerStrategy；JSON 里反斜杠要写成 \\。
        例：args 写成 ["/c", "D:\\hs-script\\plugin\\WeightHandlerStrategy\\mcp.bat"]
     换机器 / 换安装目录后只需改这一处；若打包时在脚本里填了 HOST_ROOT，这里已自动填好。
  2. 推荐用 `cmd /c <绝对路径>\mcp.bat` 启动：脚本自己会切到插件目录，进程工作目录无关紧要。
     若客户端只能直接起 java，请把 plugin-config.properties 里的相对路径改成绝对路径，
     再传 -Dapp.config.file=<绝对路径>\plugin-config.properties，并把 -cp 设成
        configUi.jar;..\WeightHandlerStrategy.jar;..\..\lib\*
  3. 重连 MCP 服务，确认工具列表里能看到 parse_hearthstone_deck_code / card_group_progress /
     strategy_coverage / get / list 等工具。

三、安装技能（让 AI 按 SOP 干活）
  把 skills\use-ai-config-generator 整个目录复制到客户端扫描 skills 的位置：
        CodeBuddy      <工作区>\.codebuddy\skills\
        Claude Code    <工作区>\.claude\skills\
        通用做法       <工作区>\.agents\skills\  ，再链接/复制到上述任一目录
  安装后，当你说「帮我配置一副卡组的策略」时该技能会被自动加载。

四、怎么用（对话示例）
  「按 SOP 配置这副卡组，卡组码：AAECAZ8F...」
  AI 会逐阶段与你对齐：进入检查（查持久层、定库、备份）→ 卡组解析 → 元数据探查 →
  领域分组 → 机制编排（combo / 光环 / 起手留牌 / 用途预设）→ 评估树文字草案确认 →
  落库并回查 → 资产沉淀。
  关键约定：分组草案、评估树草案、combo 方案都要先看文字方案再落库；落库后必须回查。

五、常见问题
  · 工具调用报 database 相关错误：确认 MCP 进程连的是哪个库（tool_capabilities 会回显
    databasePath），部署库与源库数据不同步，别写错库。
  · AI 说「工具不存在」：客户端 MCP 未连上或未重连；先看 log\mcp.log。
  · AI 生成的规则落库后没生效：引擎需要重启才会重新加载配置。
