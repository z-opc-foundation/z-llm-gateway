package com.zifang.z.llm.gateway.admin.config;

import com.zifang.z.llm.gateway.admin.security.AdminTokenFilter;
import com.zifang.z.llm.gateway.admin.security.AdminTokenGuard;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * z-llm 控制面自动装配 — 把 admin REST/UI/查询服务注册进容器.
 *
 * <p>默认不注册: 控制面能列出全部接入凭证与 vendor 凭据, 只能给部署方自己看,
 * 因此必须 {@code z.llm.expose-admin=true} 显式打开。
 *
 * <p>开关写在控制器上 (不只是这里), 因为宿主应用若自己 {@code @ComponentScan("com.zifang.z.llm.gateway")}
 * 会绕过本配置类直接扫到控制器。
 *
 * <p>前置条件: 同时需要 {@code z.llm.enabled=true} —— 控制器依赖的 LlmCredentialStore /
 * ApiKeyService / RateLimiter 都由 {@code ZLlmAutoConfiguration} 提供, 只开 expose-admin
 * 会在启动时缺 bean 直接失败 (按设计: 没有网关就没有网关的控制面)。
 *
 * <p>安全: 配了 {@code z.llm.admin-token} 时, 控制面由 {@link AdminTokenFilter} 统一鉴权;
 * 未配时仅靠 expose-admin 开关 (向后兼容).
 */
@Configuration
@ConditionalOnProperty(name = "z.llm.expose-admin", havingValue = "true")
@ComponentScan(basePackages = {
        "com.zifang.z.llm.gateway.admin.controller",
        "com.zifang.z.llm.gateway.admin.service"
})
public class ZLlmAdminAutoConfiguration {

    @Bean
    public AdminTokenGuard adminTokenGuard(GatewayProperties properties) {
        return new AdminTokenGuard(properties.getAdminToken());
    }

    /** 控制面鉴权过滤器 — 仅对 /z-llm/admin/** 生效 (内部继续按 URI 过滤). */
    @Bean
    public AdminTokenFilter adminTokenFilter(AdminTokenGuard adminTokenGuard) {
        return new AdminTokenFilter(adminTokenGuard);
    }
}
