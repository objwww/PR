package com.objwww.pr.control.alert.application.mutation;

import java.util.Optional;

/**
 * 解锁注册表端口（PD-D1，V121 mutation_unlock_registry）：scoped mutation 白名单
 * 三元（tool × resource_uid × canonical_env）——注册表外永远 dry-run。
 */
public interface UnlockScopeStore {

    record UnlockScope(String toolName, String resourceUid, String canonicalEnv,
            boolean enabled) {
    }

    Optional<UnlockScope> findByTool(String toolName);

    /** canonical env（resource_inventory 权威面，供三元比对） */
    Optional<String> envOfResource(String resourceUid);
}
