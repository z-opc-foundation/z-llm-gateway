package com.zifang.z.llm.gateway.admin.controller;

import com.zifang.z.llm.gateway.admin.service.AdminQueryService;
import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.credential.ApiKeyService;
import com.zifang.z.llm.gateway.core.registry.LlmCredentialStore;
import com.zifang.z.llm.gateway.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.gateway.core.service.RateLimiter;
import com.zifang.z.llm.gateway.core.usage.UsageLedger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 控制面 REST 端点 — 在 /z-llm/admin/* 下, 与 OpenAI/Anthropic 公网端点隔离.
 */
@RestController
@RequestMapping("/z-llm/admin")
@ConditionalOnProperty(name = "z.llm.expose-admin", havingValue = "true")
public class AdminController {

    /** 额度使用率达到该比例即告警 (前端/monitoring 高亮). */
    private static final double QUOTA_WARN_RATIO = 0.8;

    private final AdminQueryService query;
    private final LlmCredentialStore credentialStore;
    private final LlmProviderRegistry providerRegistry;
    private final ApiKeyService apiKeyService;
    private final RateLimiter rateLimiter;
    private final UsageLedger usageLedger;

    public AdminController(AdminQueryService query,
                           LlmCredentialStore credentialStore,
                           LlmProviderRegistry providerRegistry,
                           ApiKeyService apiKeyService,
                           RateLimiter rateLimiter,
                           UsageLedger usageLedger) {
        this.query = query;
        this.credentialStore = credentialStore;
        this.providerRegistry = providerRegistry;
        this.apiKeyService = apiKeyService;
        this.rateLimiter = rateLimiter;
        this.usageLedger = usageLedger;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return query.overview();
    }

    @GetMapping("/vendors")
    public List<Map<String, Object>> vendors() {
        return query.providers();
    }

    @GetMapping("/vendors/{vendor}/models")
    public Map<String, Object> models(@PathVariable String vendor) {
        Vendor v = Vendor.fromModel(vendor + "/x");
        Map<String, Object> out = new HashMap<>();
        out.put("vendor", vendor);
        out.put("models", query.models(v));
        return out;
    }

    @GetMapping("/credentials")
    public Map<String, Object> credentials() {
        Map<String, Object> out = new HashMap<>();
        out.put("total", credentialStore.size());
        out.put("byVendor", query.providers());
        return out;
    }

    /**
     * API Key 清单 — 只给掩码, 不给原文.
     *
     * <p>{@link ApiKey#getKey()} 就是鉴权用的凭证本身; 直接序列化 DTO 会让这个
     * 不带鉴权的端点变成一张"可拿来调网关的全部凭证"清单.
     */
    @GetMapping("/api-keys")
    public List<Map<String, Object>> apiKeys() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ApiKey k : apiKeyService.list()) {
            out.add(maskedView(k));
        }
        return out;
    }

    /**
     * 签发一把新 API Key (控制面的对外交付口).
     *
     * <p>body 支持: {@code name}, {@code ownerId}, {@code quota}, {@code allowedModels}(数组),
     * {@code allowedVendors}(数组), {@code tokensPerMinute}, {@code requestsPerMinute},
     * {@code expiresInDays}(从当前起算, 可选).
     *
     * <p>完整 key ({@code sk-zopc-***}) **只在本次响应返回一次**, 之后所有视图一律脱敏.
     * 已配 admin-token 时本端点受控制面鉴权保护.
     */
    @PostMapping("/api-keys")
    public Map<String, Object> issueKey(@RequestBody Map<String, Object> body) {
        ApiKey tpl = new ApiKey();
        tpl.setName(str(body.get("name")));
        tpl.setOwnerId(str(body.get("ownerId")));
        tpl.setQuota(toDouble(body.get("quota")));
        tpl.setAllowedModels(strList(body.get("allowedModels")));
        List<Vendor> vendors = strList(body.get("allowedVendors")).stream()
                .map(v -> Vendor.fromModel(v + "/x"))
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toList());
        if (!vendors.isEmpty()) {
            tpl.setAllowedVendors(vendors);
        }
        tpl.setTokensPerMinute(toLong(body.get("tokensPerMinute")));
        tpl.setRequestsPerMinute(toInt(body.get("requestsPerMinute")));
        Integer days = toInt(body.get("expiresInDays"));
        if (days != null && days > 0) {
            tpl.setExpiresAt(System.currentTimeMillis() + days * 86400_000L);
        }
        ApiKey issued = apiKeyService.issue(tpl);
        Map<String, Object> out = maskedView(issued);
        out.put("key", issued.getKey()); // 明文仅本次返回
        return out;
    }

    /** 调额/充值: body 传 {@code quota}(绝对值, 可 null=不限) 或 {@code delta}(增量). */
    @PostMapping("/api-keys/{id}/quota")
    public Map<String, Object> updateQuota(@PathVariable String id, @RequestBody Map<String, Object> body) {
        ApiKey k;
        if (body != null && body.containsKey("quota")) {
            k = apiKeyService.setQuota(id, toDouble(body.get("quota")));
        } else if (body != null && body.containsKey("delta")) {
            Double d = toDouble(body.get("delta"));
            k = apiKeyService.topUp(id, d == null ? 0d : d);
        } else {
            throw GatewayException.invalidRequest("provide 'quota' (absolute) or 'delta' (increment)");
        }
        return maskedView(k);
    }

    /** 停用某 key: 立即从生效表移除, 之后鉴权失败. */
    @PostMapping("/api-keys/{id}/disable")
    public Map<String, Object> disableKey(@PathVariable String id) {
        if (!apiKeyService.disable(id)) {
            throw new GatewayException(404, "api_key_not_found", "API key not found: " + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "disabled");
        return out;
    }

    private static Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Double.valueOf(s);
    }

    private static Long toLong(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Long.valueOf(s);
    }

    private static Integer toInt(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : Integer.valueOf(s);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static List<String> strList(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Collection<?>) {
            List<String> out = new ArrayList<>();
            for (Object o : (Collection<?>) v) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
            return out.isEmpty() ? null : out;
        }
        return null;
    }

    private Map<String, Object> maskedView(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("ownerId", k.getOwnerId());
        m.put("status", k.getStatus());
        m.put("maskedKey", mask(k.getKey()));
        m.put("expiresAt", k.getExpiresAt());
        m.put("allowedModels", k.getAllowedModels());
        m.put("allowedVendors", k.getAllowedVendors());
        m.put("tokensPerMinute", k.getTokensPerMinute());
        m.put("requestsPerMinute", k.getRequestsPerMinute());
        // 预付额度对账: 上限 / 已用(来自账本) / 剩余
        double used = usageLedger == null ? 0d : usageLedger.totalCost(k);
        Double quota = k.getQuota();
        m.put("quota", quota);
        m.put("used", used);
        m.put("remaining", quota == null ? null : Math.max(0d, quota - used));
        // 额度告警阈值: 达到上限 80% 即 warning, 前端可直接高亮
        m.put("usageRatio", quota == null || quota <= 0 ? 0d : Math.min(1d, used / quota));
        m.put("warning", quota != null && quota > 0 && used >= quota * QUOTA_WARN_RATIO);
        m.put("createdAt", k.getCreatedAt());
        return m;
    }

    /** 留头尾各 4 位供人工对号, 中间一律打星. */
    static String mask(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    @GetMapping("/rate-limit")
    public Object rateLimit() {
        return rateLimiter.snapshot();
    }

    /**
     * 用量与花费台账 (按 ApiKey / 按 ApiKey+model 两级).
     *
     * <p>原先 {@link UsageLedger#snapshot()} 有实现但没有任何 controller 调用点 ⇒
     * 记账口是摆设; 补上这个控制面端点后, 运维/调用方都能按 key 精确对账烧了多少钱.
     * 走 /z-llm/admin/* 且由 {@code z.llm.expose-admin=true} 门控, 与公网 /v1 隔离.
     */
    @GetMapping("/usage")
    public Map<String, Object> usage() {
        return usageLedger.snapshot();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new HashMap<>();
        out.put("status", "UP");
        out.put("providers", providerRegistry.keys().size());
        out.put("credentials", credentialStore.size());
        return out;
    }
}