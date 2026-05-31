package lin.provider

import lin.bean.usePlan.GroupUseOverride
import lin.domain.use.plan.GroupUseOverrideProvider
import lin.group_use_override.db.GroupUseOverrideRepository

class SqliteGroupUseOverrideProvider(
    private val repository: GroupUseOverrideRepository
) : GroupUseOverrideProvider {
    override fun overridesOf(groupIds: Set<String>): Map<String, GroupUseOverride> {
        if (groupIds.isEmpty()) return emptyMap()
        return repository.findByCardGroupIds(groupIds).associate { it.cardGroupId to it.toDomain() }
    }
}
