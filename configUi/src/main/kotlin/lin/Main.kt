package lin

import javafx.application.Application
import javafx.scene.Scene
import javafx.stage.Stage
import lin.moduls.ModelsDefine
import lin.ui.MainShellView
import org.koin.core.context.GlobalContext.stopKoin

class ConfigUiApp : Application() {
    override fun init() {
        super.init()
        // 启动依赖注入
        ModelsDefine().loadModules()
    }

    override fun start(primaryStage: Stage) {
        val shellView = MainShellView()

        val scene = Scene(shellView, 1200.0, 800.0)
        // 注入简单的 CSS 用于导航按钮样式
        scene.stylesheets.add(
            "data:text/css," +
                    ".nav-button { -fx-font-size: 14px; -fx-padding: 10 20 10 20; -fx-background-color: transparent; -fx-text-fill: #333; -fx-alignment: center-left; }" +
                    ".nav-button:hover { -fx-background-color: #e0e0e0; }" +
                    ".nav-button-selected { -fx-background-color: #d0d0d0; -fx-font-weight: bold; }"
        )

        primaryStage.title = "WeightHandler Strategy Configurator"
        primaryStage.scene = scene
        primaryStage.show()
    }

    override fun stop() {
        super.stop()
        stopKoin()
    }
}

fun main(args: Array<String>) {
    Application.launch(ConfigUiApp::class.java, *args)
}
