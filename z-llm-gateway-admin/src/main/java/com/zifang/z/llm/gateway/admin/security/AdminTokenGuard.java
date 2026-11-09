package com.zifang.z.llm.gateway.admin.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 控制面管理令牌校验 (纯逻辑, 可单测).
 *
 * <p>{@code z.llm.admin-token} 非空时, /z-llm/admin/** 必须带匹配令牌, 否则 401.
 * 比较用 {@link MessageDigest#isEqual} 做常量时间, 避免时序侧信道.
 *
 * <p>令牌为空 = 未启用校验 (向后兼容: 只有 expose-admin 开关的门控),
 * 这种情况 authorize 恒 true.
 */
public final class AdminTokenGuard {

    private final String configured;

    public AdminTokenGuard(String configured) {
        this.configured = configured;
    }

    /** 是否启用了令牌校验. */
    public boolean enabled() {
        return configured != null && !configured.trim().isEmpty();
    }

    /** 未启用校验时放行; 启用时须与配置令牌常量时间相等. */
    public boolean authorize(String provided) {
        if (!enabled()) {
            return true;
        }
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                configured.getBytes(StandardCharsets.UTF_8),
                provided.trim().getBytes(StandardCharsets.UTF_8));
    }
}