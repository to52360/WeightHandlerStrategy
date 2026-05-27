package lin.bean.usePlan

import lin.bean.ComboCard

sealed interface SelectConstraint

data class MutexCoreGroups(
    val groupIds: Set<String>,
    val reason: String
) : SelectConstraint

sealed interface UseConstraint

data class MustUseBefore(
    val before: ComboCard,
    val after: ComboCard,
    val reason: String
) : UseConstraint
