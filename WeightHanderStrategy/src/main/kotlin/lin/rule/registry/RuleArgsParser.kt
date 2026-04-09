package lin.rule.registry

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlin.reflect.KClass

val defaultRuleArgsObjectMapper: ObjectMapper = JsonMapper.builder()
    .addModule(KotlinModule.Builder().build())
    .addModule(JavaTimeModule())
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .build()

fun <T : Any> mapToRuleArgs(
    args: Map<String, Any>,
    parameterType: KClass<T>,
    objectMapper: ObjectMapper = defaultRuleArgsObjectMapper
): T {
    return objectMapper.convertValue(args, parameterType.java)
}

inline fun <reified T : Any> mapToRuleArgs(
    args: Map<String, Any>,
    objectMapper: ObjectMapper = defaultRuleArgsObjectMapper
): T {
    return mapToRuleArgs(args, T::class, objectMapper)
}
