============================================================
 Deck-Plugin-Market 插件包 —— 安装说明
============================================================

一、包内容
  plugin\WeightHandlerStrategy.jar                        引擎插件（宿主唯一入口，已内置 koin）
  plugin\WeightHandlerStrategy\configUi.jar               MCP 服务端 + 配置侧扩展 + 图形界面（依赖已全部打进 jar）
  plugin\WeightHandlerStrategy\mcp.bat                    MCP 启动脚本（AI 客户端调用）
  plugin\WeightHandlerStrategy\ui.bat                     图形界面启动脚本（双击可用）
  plugin\WeightHandlerStrategy\logback.xml                MCP/UI 进程日志配置
  plugin\WeightHandlerStrategy\plugin-config.properties   路径配置（包内已填好，见第三节）
  plugin\WeightHandlerStrategy\weightHandlerStrategy.db   引擎业务配置库（决策树 / 卡组 / combo / 光环…）
  data\cardgroup\*.cardgroup                              卡组文件目录
  README-INSTALL.txt                                      本文件

  不含 hs_cards.db：卡牌数据仓库由宿主自带，本插件只读，不参与分发。
  也不含第三方依赖目录：宿主 lib 已提供 kotlin / jackson / spring / slf4j / logback / sqlite /
  javafx 等公共库；本插件特有依赖（MCP SDK、koin、victools、HikariCP、reactor、snakeyaml、
  itu、stately…）已全部 shade 进两个 jar。
  （mcp.bat / ui.bat 的 classpath 里另留了一个可选的 mcp-lib\* 槽位，供本地开发用
   「未 shade 的 jar + 手工依赖目录」时补依赖；发布包里没有这个目录，java 静默忽略。）

二、安装（三步）
  1. 把本压缩包的内容解压到「宿主根目录」——即含 hs-script.exe 的那一级目录。
     解压后应得到：<宿主根目录>\plugin\WeightHandlerStrategy.jar
                   <宿主根目录>\plugin\WeightHandlerStrategy\configUi.jar
  2. 重启宿主（双击 hs-script.bat / hs-script.exe）让新的引擎插件生效。
  3. 在 AI 客户端里重连 MCP 服务（工具列表会刷新）。

三、路径配置（plugin-config.properties）
  三项键值已按「相对本目录」填好，解压后无需修改：

  | 键                  | 本包默认值               | 含义                                |
  |---------------------|--------------------------|-------------------------------------|
  | database.path       | weightHandlerStrategy.db | 引擎业务配置库（本目录内）          |
  | hs_cards.db.path    | ../../hs_cards.db        | 卡牌数据仓库（宿主根目录，宿主自带）|
  | cardgroup.dir.path  | ../../data/cardgroup     | 卡组文件目录（宿主根目录）          |

  相对值以「本目录」为基准，因为 mcp.bat / ui.bat 启动前会先切到本目录 —— 整个插件目录跟着
  宿主根目录一起搬，路径无需改动。
  注意：宿主引擎进程不读这个文件，它按自己的启动目录推导
        <启动目录>\plugin\WeightHandlerStrategy\weightHandlerStrategy.db
  因此宿主必须从根目录启动（双击 hs-script.bat 即为根目录）。
  只有当 AI 客户端「自己直接起 java」而不用 mcp.bat 时，才需要把上表三项改成绝对路径。

四、日志
  MCP / UI 进程：本目录 log\mcp.log
  引擎进程：宿主根目录 log\weightHandler.log
  ⚠️ 宿主根目录的 logback.xml 里必须有 lin 包的 logger / appender，否则引擎日志会被根级别
     过滤掉，表现为「引擎没有日志」。

五、覆盖警告（重要）
  本包内含 weightHandlerStrategy.db 与 data\cardgroup 两份「数据」，解压会覆盖同名文件。
  · 只想升级程序：只取两个 jar 与 mcp.bat / ui.bat / logback.xml / plugin-config.properties。
  · 只想升级配置：只取 weightHandlerStrategy.db 与 data\cardgroup。
  · 覆盖前建议先手工备份（本项目不生成 .bak-* 备份文件）。

六、环境要求
  · Windows 10 / 11
  · 宿主 HS-Script v4.16.x（宿主自带 JRE 即可，无需单独安装 JDK）

七、排错
  · 双击 mcp.bat 闪一下就退：它是 stdio 服务，本来就该由 AI 客户端拉起；手动运行时看到
    等待输入即为正常。
  · 工具列表里缺 tool：① 客户端 MCP 配置里 mcp.bat 的路径是否为绝对路径；
    ② 本目录是否同时存在 configUi.jar 与 ..\WeightHandlerStrategy.jar；
    ③ 看 log\mcp.log 与宿主 log\weightHandler.log。
  · 引擎加载了插件但「评估树 / 卡组 / combo 全部失效」：说明 configUi.jar 没被引擎扫到，
    它必须位于 <引擎 jar 所在目录>\WeightHandlerStrategy\ 下。
