package com.zifang.z.llm.gateway.core.credential.persist;

import com.zifang.z.llm.gateway.api.dto.ApiKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * {@code ApiKeyService} 运行期状态（已签发 key + 额度/停用覆盖）的持久化口.
 *
 * <p>ApiKeyService 本期以内存形态运行为主（与限流/台账一致）; 需要“重启不丢”时,
 * 由实现方把这一份“运行期增量”落盘并在启动时回灌. 接口刻意做成**幂等快照**语义:
 * save 总是写入全量增量, load 一次性读回, 不暴露目录/路径等部署细节.
 */
public interface ApiKeyPersistence {

    /**
     * 运行期增量快照.
     *
     * @param issued   已签发（runtime 注册）的 key, 含完整凭据, 用于重启后重建鉴权表.
     * @param overrides 配置面 key 被运行期覆盖过的条目 (额度和/或状态), 用于重启后合并回配置 key.
     */
    void save(List<ApiKey> issued, List<ApiKey> overrides);

    /** 读回上一次保存的快照; 无历史返回两个空列表 (不得返回 null). */
    Snapshot load();

    /** 快照容器. */
    final class Snapshot {
        private final List<ApiKey> issued;
        private final List<ApiKey> overrides;

        public Snapshot(List<ApiKey> issued, List<ApiKey> overrides) {
            this.issued = issued == null ? Collections.emptyList() : new ArrayList<>(issued);
            this.overrides = overrides == null ? Collections.emptyList() : new ArrayList<>(overrides);
        }

        public static Snapshot empty() {
            return new Snapshot(Collections.emptyList(), Collections.emptyList());
        }

        public List<ApiKey> issued() {
            return issued;
        }

        public List<ApiKey> overrides() {
            return overrides;
        }
    }
}