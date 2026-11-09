package com.zifang.z.llm.gateway.core.credential.persist;

import com.zifang.z.llm.gateway.api.dto.ApiKey;

import java.util.List;

/**
 * 不落盘的默认实现 — 保持“运行期变更仅存内存”的既有行为 (向后兼容).
 */
public class NoopApiKeyPersistence implements ApiKeyPersistence {

    @Override
    public void save(List<ApiKey> issued, List<ApiKey> overrides) {
        // no-op
    }

    @Override
    public Snapshot load() {
        return Snapshot.empty();
    }
}