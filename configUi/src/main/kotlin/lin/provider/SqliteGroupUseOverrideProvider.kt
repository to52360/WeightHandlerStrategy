package lin.provider

import lin.bean.usePlan.GroupUseOverride
import lin.group_use_override.db.GroupUseOverrideRepository
import lin.serviceLoader.provider.config.GroupUseOverrideProvider

class SqliteGroupUseOverrideProvider(
    private val repository: GroupUseOverrideRepository
) : GroupUseOverrideProvider {
    override fun findAllEnabled(): Map<String, GroupUseOverride> {
        return repository.findAll().associate { it.cardGroupId to it.toDomain() }
    }
}
