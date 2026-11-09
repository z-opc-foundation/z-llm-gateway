package com.zifang.z.llm.gateway.config.service;

import com.zifang.z.llm.gateway.api.dto.LlmCredential;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.config.entity.LlmModel;
import com.zifang.z.llm.gateway.config.entity.LlmProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DB 里的 Provider/Model 元数据 → 网关运行时 {@link LlmCredential} 的映射 (纯函数, 可单测).
 *
 * <p>一条 provider 产出一条凭据:
 * <ul>
 *   <li>alias = providerCode (路由键)</li>
 *   <li>vendor = 由 providerType 解析 (openai/anthropic/deepseek/qwen/dashscope/gemini)</li>
 *   <li>apiKey / baseUrl / priority 原样透传</li>
 *   <li>allowedModels = 该 provider 下 enabled 的 modelCode 集合 (服务能力白名单)</li>
 *   <li>status = enabled ? active : disabled</li>
 * </ul>
 *
 * <p>不支持/未知的 vendor 直接跳过, 不产出凭据 —— 不能让一段无效配置把整个网关弄挂.
 */
public final class ManagedCredentialMapper {

    private ManagedCredentialMapper() {
    }

    /**
     * 由 DB 的 providers + models 构造受管凭据列表.
     *
     * @param providers 全部供应商 (含 enabled=0 的, 由 status 表达禁用)
     * @param models    全部模型 (含 enabled=0 的)
     */
    public static List<LlmCredential> toCredentials(List<LlmProvider> providers, List<LlmModel> models) {
        // providerCode → enabled modelCode 集合
        java.util.Map<String, List<String>> modelsByProvider = new java.util.LinkedHashMap<>();
        if (models != null) {
            for (LlmModel m : models) {
                if (m == null || m.getModelCode() == null || m.getProviderCode() == null) {
                    continue;
                }
                if (!isEnabled(m.getEnabled())) {
                    continue;
                }
                modelsByProvider
                        .computeIfAbsent(m.getProviderCode(), k -> new ArrayList<>())
                        .add(m.getModelCode());
            }
        }

        List<LlmCredential> out = new ArrayList<>();
        if (providers == null) {
            return out;
        }
        for (LlmProvider p : providers) {
            if (p == null || !StringUtils.hasText(p.getProviderCode())) {
                continue;
            }
            Vendor vendor = resolveVendor(p.getProviderType());
            if (vendor == null) {
                continue;
            }
            LlmCredential c = new LlmCredential();
            c.setAlias(p.getProviderCode());
            c.setVendor(vendor);
            c.setApiKey(p.getApiKey());
            c.setBaseUrl(p.getBaseUrl());
            c.setPriority(p.getPriority());
            c.setStatus(isEnabled(p.getEnabled()) ? "active" : "disabled");
            List<String> allowed = modelsByProvider.get(p.getProviderCode());
            if (allowed != null && !allowed.isEmpty()) {
                c.setAllowedModels(new ArrayList<>(new HashSet<>(allowed)));
            }
            out.add(c);
        }
        return out;
    }

    /** providerType → Vendor; 未知/不支持返回 null. */
    public static Vendor resolveVendor(String providerType) {
        if (!StringUtils.hasText(providerType)) {
            return null;
        }
        String t = providerType.trim();
        for (Vendor v : Vendor.values()) {
            if (v.code().equalsIgnoreCase(t)) {
                return v;
            }
        }
        return null;
    }

    private static boolean isEnabled(Integer enabled) {
        return Objects.equals(enabled, 1);
    }
}