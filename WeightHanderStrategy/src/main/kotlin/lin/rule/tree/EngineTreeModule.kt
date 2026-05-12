package lin.rule.tree

import lin.moduls.treeConfigProviderModule
import org.koin.dsl.module

val engineTreeModule = module {
    treeConfigProviderModule
    single { EvaluatorTreeInstantiator(get()) }
}
