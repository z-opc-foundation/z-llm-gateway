package com.zifang.z.llm.gateway.core.credential;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.credential.persist.ApiKeyPersistence;
import com.zifang.z.llm.gateway.core.credential.persist.NoopApiKeyPersistence;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

/**
 * API Key 校验服务 — 由网关签发/管理的接入凭证.
 *
 * <p>鉴权流程: 调用方在 Authorization header 带 "Bearer {api_key}",
 * <p>本服务用 sha256 前缀匹配 + 常量时间比较查找 ApiKey.
 *
 * <p>本期由配置文件提供 ApiKey 列表 (后续由 z-llm-admin 通过 zk-config 动态刷新).
 *
 * <p>实现 InitializingBean 是因为 @ConfigurationProperties 的字段绑定发生在
 * <p>bean 构造之后, 若在构造里读 properties.getApiKeys() 会得到空 list.
 */
public class ApiKeyService implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final GatewayProperties properties;
    private final ConcurrentHashMap<String, ApiKey> activeKeys = new ConcurrentHashMap<>();
    /** 运行期注册的 key (平台 AK 桥接); reload() 只清配置 key, 这批要存活. */
    private final ConcurrentHashMap<String, ApiKey> runtimeKeys = new ConcurrentHashMap<>();
    /** 运行期被额度/停用覆盖过的 key id — 参与持久化, 重启后合并回配置 key. */
    private final Set<String> mutatedIds = new LinkedHashSet<>();
    private final ApiKeyPersistence persistence;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(GatewayProperties properties) {
        this(properties, new NoopApiKeyPersistence());
    }

    public ApiKeyService(GatewayProperties properties, ApiKeyPersistence persistence) {
        this.properties = properties;
        this.persistence = persistence == null ? new NoopApiKeyPersistence() : persistence;
    }

    @Override
    public void afterPropertiesSet() {
        reload();
        restoreRuntime();
    }

    public synchronized void reload() {
        activeKeys.clear();
        if (properties.getApiKeys() != null) {
            for (ApiKey k : properties.getApiKeys()) {
                if (k.getKey() == null || k.getKey().isEmpty()) continue;
                if (!"active".equalsIgnoreCase(k.getStatus())) continue;
                if (k.getId() == null || k.getId().isEmpty()) {
                    k.setId(propertyKeyId(k));
                }
                activeKeys.put(k.getKey(), k);
            }
        }
        // reload 只管配置面; 运行期注册的 key (平台 AK) 不受配置刷新影响
        activeKeys.putAll(runtimeKeys);
        log.info("ApiKeyService loaded active keys: {} (config {}, runtime {})",
                activeKeys.size(),
                properties.getApiKeys() == null ? 0 : properties.getApiKeys().size(),
                runtimeKeys.size());
    }

    /**
     * 从持久化恢复运行期状态（已签发 key 重建鉴权表; 额度/停用覆盖合并回配置 key）.
     */
    synchronized void restoreRuntime() {
        ApiKeyPersistence.Snapshot snap = persistence.load();
        for (ApiKey k : snap.issued()) {
            register(k);
        }
        for (ApiKey ov : snap.overrides()) {
            ApiKey existing = findByKeyId(ov.getId());
            if (existing == null) {
                continue;
            }
            if (ov.getQuota() != null) {
                existing.setQuota(ov.getQuota());
            }
            if (ov.getStatus() != null && !"active".equalsIgnoreCase(ov.getStatus())) {
                existing.setStatus(ov.getStatus());
                activeKeys.remove(existing.getKey());
                runtimeKeys.remove(existing.getKey());
            }
            mutatedIds.add(ov.getId());
        }
        if (!snap.issued().isEmpty() || !snap.overrides().isEmpty()) {
            log.info("restored runtime api-key state: issued {}, overrides {}",
                    snap.issued().size(), snap.overrides().size());
        }
    }

    /**
     * 运行期注册一把 key (平台 AK 桥接: z-agent 建的 AK 要能直接调网关).
     * 同一 secret 再注册时覆盖旧条目; 返回是否生效 (disabled 的 key 不注册).
     */
    public synchronized boolean register(ApiKey k) {
        if (k == null || k.getKey() == null || k.getKey().isEmpty()) {
            return false;
        }
        if (k.getStatus() != null && !"active".equalsIgnoreCase(k.getStatus())) {
            unregister(k.getKey());
            return false;
        }
        if (k.getId() == null || k.getId().isEmpty()) {
            k.setId(propertyKeyId(k));
        }
        runtimeKeys.put(k.getKey(), k);
        activeKeys.put(k.getKey(), k);
        return true;
    }

    /**
     * 运行时签发一把新 key (控制面 POST /api-keys 用).
     *
     * <p>id 与 key 都由网关生成: {@code ak-<hex>} 作为内部 id, {@code sk-zopc-<base62>} 作为
     * 鉴权凭据 (调用方唯一需要保存的明文). 签发即注册进生效表, 下一个请求就能用.
     *
     * @return 已填充的 ApiKey (key 为完整明文, 只在本次返回, 之后一律脱敏)
     */
    public synchronized ApiKey issue(ApiKey template) {
        String secret = "sk-zopc-" + randomToken();
        String id = "ak-" + randomToken();
        template.setKey(secret);
        template.setId(id);
        if (template.getStatus() == null || template.getStatus().isEmpty()) {
            template.setStatus("active");
        }
        if (template.getCreatedAt() == null) {
            template.setCreatedAt(System.currentTimeMillis());
        }
        if (!register(template)) {
            throw new IllegalStateException("failed to register issued api key");
        }
        persist();
        log.info("issued api key {} (quota={})", id, template.getQuota());
        return template;
    }

    private String randomToken() {
        byte[] b = new byte[12];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder(16);
        String alphabet = "0123456789abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ";
        for (byte x : b) {
            int v = (x & 0xFF) % alphabet.length();
            sb.append(alphabet.charAt(v));
        }
        return sb.toString();
    }

    /**
     * 把运行期增量写进持久化: issued(已签发运行期 key) + overrides(被改过额度/状态的).
     */
    private synchronized void persist() {
        List<ApiKey> issued = new ArrayList<>(runtimeKeys.values());
        List<ApiKey> overrides = new ArrayList<>();
        for (String id : mutatedIds) {
            ApiKey k = findByKeyId(id);
            if (k != null) {
                overrides.add(k);
            }
        }
        persistence.save(issued, overrides);
    }

    private void track(ApiKey k) {
        if (k.getId() != null) {
            mutatedIds.add(k.getId());
        }
    }

    /** 按 secret 注销; 返回是否真的移除了条目. */
    public synchronized boolean unregister(String secret) {
        if (secret == null || secret.isEmpty()) {
            return false;
        }
        boolean removed = runtimeKeys.remove(secret) != null;
        ApiKey gone = activeKeys.remove(secret);
        return removed || gone != null;
    }

    /**
     * properties 里定义的 key 没有 id, 而下游全按 id 归桶: RateLimiter 拿 null 去
     * {@code ConcurrentHashMap.computeIfAbsent} 直接 NPE (请求面表现是 /v1/* 一律 500),
     * UsageLedger 则静默丢掉整条用量. 补在装载处而不是各调用点各自判空 ——
     * 「配置文件提供 ApiKey」是本期唯一一条真实路径, 配出来就坏的 key 不该要求使用者知道要补 id.
     * <p>id 会进限流日志和 admin 输出, 所以取 key 的摘要而非明文; 摘要稳定, 重启后桶不漂移.
     */
    static String propertyKeyId(ApiKey k) {
        StringBuilder sb = new StringBuilder("property:");
        if (k.getName() != null && !k.getName().isEmpty()) {
            sb.append(k.getName()).append(':');
        }
        return sb.append(digest(k.getKey())).toString();
    }

    private static String digest(String raw) {
        byte[] hash;
        try {
            hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 6; i++) {
            sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(hash[i] & 0xF, 16));
        }
        return sb.toString();
    }

    /**
     * 校验 Bearer token. 返回 ApiKey 表示通过; 返回 null 表示失败 (调用方自行决定抛出 401).
     */
    public ApiKey authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isEmpty()) {
            return null;
        }
        // 1. 直接匹配 (静态配置场景)
        ApiKey direct = activeKeys.get(bearerToken);
        if (direct != null) {
            return checkExpiry(direct);
        }
        // 2. 兼容带 "Bearer " 前缀
        String stripped = bearerToken.startsWith("Bearer ")
                ? bearerToken.substring(7).trim()
                : bearerToken;
        ApiKey strippedHit = activeKeys.get(stripped);
        if (strippedHit != null) {
            return checkExpiry(strippedHit);
        }
        return null;
    }

    private ApiKey checkExpiry(ApiKey k) {
        if (k.getExpiresAt() != null && System.currentTimeMillis() > k.getExpiresAt()) {
            throw GatewayException.unauthorized("API key expired");
        }
        return k;
    }

    public List<ApiKey> list() {
        return java.util.Collections.unmodifiableList(
                new java.util.ArrayList<>(activeKeys.values()));
    }

    /**
     * 租户隔离强制点: 鉴权拿到 key 后调用 (从请求头取租户).
     *
     * <p>语义: 仅当 {@code z.llm.tenant-isolation-enabled=true} 且该 key 绑定了非空
     * {@code tenantCode} 时生效 —— 请求 {@code Tenant-Code} 头缺失或与 key 绑定的租户
     * 不一致 ⇒ 403 forbidden. 平台级 key (未绑定租户) 或未开启隔离时放行 (向后兼容).
     *
     * <p>返回 key 本身便于链式/断言; 异常走 {@link GatewayException#forbidden}.
     */
    public ApiKey enforceTenant(HttpServletRequest req, ApiKey key) {
        if (req == null) {
            return key;
        }
        return enforceTenant(req.getHeader(properties.getTenantHeader()), key);
    }

    /** 租户隔离强制点的字符串重载 (供测试/非 servlet 链路直接按其租户判定). */
    public ApiKey enforceTenant(String requestTenantCode, ApiKey key) {
        if (key == null) {
            return null;
        }
        if (!properties.isTenantIsolationEnabled()) {
            return key;
        }
        String bound = key.getTenantCode();
        if (bound == null || bound.isEmpty()) {
            return key;  // 平台级/通用 key, 不做租户绑定校验
        }
        if (requestTenantCode == null || requestTenantCode.trim().isEmpty()
                || !bound.trim().equalsIgnoreCase(requestTenantCode.trim())) {
            throw GatewayException.forbidden(
                    "tenant mismatch: key is bound to tenant '" + bound
                            + "', request Tenant-Code='" + requestTenantCode + "'");
        }
        return key;
    }

    /** 只校验不抛 (null 安全), 供 AccessControl/单测做布尔判定. */
    public boolean allowedForTenant(String requestTenantCode, ApiKey key) {
        if (key == null) {
            return false;
        }
        if (!properties.isTenantIsolationEnabled()) {
            return true;
        }
        String bound = key.getTenantCode();
        if (bound == null || bound.isEmpty()) {
            return true;
        }
        if (requestTenantCode == null || requestTenantCode.trim().isEmpty()) {
            return false;
        }
        return bound.trim().equalsIgnoreCase(requestTenantCode.trim());
    }

    /** 按 keyId 查当前生效的 key (鉴权能命中的那批). 找不到返回 null. */
    public ApiKey findByKeyId(String keyId) {
        if (keyId == null || keyId.isEmpty()) {
            return null;
        }
        for (ApiKey k : activeKeys.values()) {
            if (keyId.equals(k.getId())) {
                return k;
            }
        }
        return null;
    }

    /**
     * 设定某 key 的额度上限 (null = 不限).
     *
     * <p>直接改 {@link #activeKeys} 里那个 ApiKey 实例本身 —— 鉴权返回的就是同一引用,
     * 因此下一个请求上的额度校验立刻读到新值, 无需重启或 reload.
     *
     * @return 更新后的 key
     */
    public synchronized ApiKey setQuota(String keyId, Double quota) {
        ApiKey k = requireKey(keyId);
        k.setQuota(quota);
        track(k);
        persist();
        log.info("api key {} quota set to {}", keyId, quota);
        return k;
    }

    /**
     * 额度充值/调整: 在当前 quota 上加减 (delta 为负即扣减, 结果不小于 0; 原为不限则按 0 起算再调).
     *
     * @return 更新后的 key
     */
    public synchronized ApiKey topUp(String keyId, double delta) {
        ApiKey k = requireKey(keyId);
        double base = k.getQuota() == null ? 0d : k.getQuota();
        double next = Math.max(0d, base + delta);
        k.setQuota(next);
        track(k);
        persist();
        log.info("api key {} quota top-up {} => {}", keyId, delta, next);
        return k;
    }

    /** 停用某 key: 置 disabled 并从生效表移除 (之后的鉴权直接失败). */
    public synchronized boolean disable(String keyId) {
        ApiKey k = findByKeyId(keyId);
        if (k == null) {
            return false;
        }
        k.setStatus("disabled");
        activeKeys.remove(k.getKey());
        runtimeKeys.remove(k.getKey());
        track(k);
        persist();
        return true;
    }

    private ApiKey requireKey(String keyId) {
        ApiKey k = findByKeyId(keyId);
        if (k == null) {
            throw new GatewayException(404, "api_key_not_found", "API key not found: " + keyId);
        }
        return k;
    }
}