package com.zifang.z.llm.gateway.core.credential.persist;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zifang.z.llm.gateway.api.dto.ApiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * 落地到 JSON 文件的持久化 — 把运行期增量写成 {@code {"issued":[...],"overrides":[...]}},
 * 并做「写临时文件 + 原子 move」防止写一半留下损坏文件.
 *
 * <p>持久化的是**完整凭据** (含 issued key 的 secret): 签发出去的 key 重启后要靠它重建鉴权表,
 * 因此本文件等同密钥库, 路径 {@code z.llm.api-key-store-path} 必须放到运行方能管控的位置.
 */
public class JsonFileApiKeyPersistence implements ApiKeyPersistence {

    private static final Logger log = LoggerFactory.getLogger(JsonFileApiKeyPersistence.class);

    private final Path storePath;
    private final ObjectMapper mapper;

    public JsonFileApiKeyPersistence(String storePath) {
        this(java.nio.file.Paths.get(storePath));
    }

    public JsonFileApiKeyPersistence(Path storePath) {
        this.storePath = storePath;
        this.mapper = new ObjectMapper();
    }

    @Override
    public synchronized void save(List<ApiKey> issued, List<ApiKey> overrides) {
        try {
            if (storePath.getParent() != null) {
                Files.createDirectories(storePath.getParent());
            }
            Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
            byte[] bytes = mapper.writeValueAsBytes(Map.of("issued", issued, "overrides", overrides));
            Files.write(tmp, bytes);
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            log.info("persisted runtime api-key state to {} (issued {}, overrides {})",
                    storePath, issued.size(), overrides.size());
        } catch (IOException e) {
            // 持久化失败不阻断主流程: 网关仍照常服务, 只是重启后这次变更不保留.
            log.warn("failed to persist api-key state to {}: {}", storePath, e.toString());
        }
    }

    @Override
    public synchronized Snapshot load() {
        if (!Files.exists(storePath)) {
            return Snapshot.empty();
        }
        try {
            byte[] bytes = Files.readAllBytes(storePath);
            if (bytes.length == 0) {
                return Snapshot.empty();
            }
            Map<String, List<ApiKey>> doc = mapper.readValue(bytes,
                    new TypeReference<Map<String, List<ApiKey>>>() { });
            return new Snapshot(
                    doc.getOrDefault("issued", List.of()),
                    doc.getOrDefault("overrides", List.of()));
        } catch (IOException e) {
            log.warn("failed to load api-key state from {}: {}", storePath, e.toString());
            return Snapshot.empty();
        }
    }
}