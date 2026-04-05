package lin.rule.registry

fun interface RuleArgsParser<T : Any> {
    fun parse(args: Map<String, Any>): T
}

fun <T : Any> ruleArgsParser(parser: RuleArgsReader.() -> T): RuleArgsParser<T> {
    return RuleArgsParser { args -> RuleArgsReader(args).parser() }
}

class RuleArgsReader(
    private val args: Map<String, Any>
) {
    fun int(name: String, default: Int = 0): Int {
        return (args[name] as? Number)?.toInt()
            ?: args[name]?.toString()?.toIntOrNull()
            ?: default
    }

    fun bool(name: String, default: Boolean = false): Boolean {
        val value = args[name]
        if (value is Boolean) return value
        return value?.toString()?.toBooleanStrictOrNull() ?: default
    }

    fun string(name: String, default: String = ""): String {
        return args[name]?.toString() ?: default
    }
}
