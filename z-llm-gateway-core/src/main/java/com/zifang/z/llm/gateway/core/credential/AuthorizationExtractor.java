package com.zifang.z.llm.gateway.core.credential;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import org.springframework.http.HttpHeaders;

import jakarta.servlet.http.HttpServletRequest;

/**
 * HTTP Authorization header → ApiKey 提取.
 */
public final class AuthorizationExtractor {

    private AuthorizationExtractor() {}

    /**
     * 解析请求里的 Authorization header, 返回 ApiKey (未通过校验抛 401).
     */
    public static ApiKey requireApiKey(HttpServletRequest req, ApiKeyService service) {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || header.isEmpty()) {
            header = req.getHeader("x-api-key"); // Anthropic 客户端常用此 header
        }
        if (header == null || header.isEmpty()) {
            throw GatewayException.unauthorized("Missing Authorization header");
        }
        // 去掉 "Bearer " 前缀
        String token = header.startsWith("Bearer ")
                ? header.substring(7).trim()
                : header.trim();
        ApiKey key = service.authenticate(token);
        if (key == null) {
            throw GatewayException.unauthorized("Invalid API key");
        }
        // 租户隔离: 开启且 key 绑定了租户时, 请求 Tenant-Code 必须一致 (否则 403).
        service.enforceTenant(req, key);
        return key;
    }

    /**
     * 从请求里读取租户隔离用的 {@code Tenant-Code} 头 (取不到返回 null).
     *
     * <p>租户隔离由 AccessControl.authorizeTenant 在鉴权时用它比对 key 绑定的租户.
     */
    public static String tenantCode(HttpServletRequest req, String tenantHeader) {
        if (req == null) {
            return null;
        }
        String name = (tenantHeader == null || tenantHeader.isEmpty()) ? "Tenant-Code" : tenantHeader;
        String v = req.getHeader(name);
        return (v == null || v.trim().isEmpty()) ? null : v.trim();
    }
}