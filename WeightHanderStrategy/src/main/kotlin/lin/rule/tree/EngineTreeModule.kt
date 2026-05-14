package lin.rule.tree

import org.koin.dsl.module

val engineTreeModule = module {
    single { EvaluatorTreeInstantiator(get()) }
}
