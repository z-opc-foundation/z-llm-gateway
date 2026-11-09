package com.zifang.z.llm.gateway.config.auth;

import com.zifang.z.llm.gateway.api.exception.GatewayException;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 管理面 (config) 的写操作门卫 — 运营权限判定.
 *
 * <p>用户/租户 (USER) 可以读配置; 创建/更新/删除这类写操作仅允许运营 (OPERATOR).
 * 角色由 {@link LlmConfigRoleResolver} 提供, 默认实现始终返回 OPERATOR,
 * 部署方接入宿主的 RBAC 后即生效.
 */
@Component("llmConfigAccess")
public class LlmConfigAccess {

    private final LlmConfigRoleResolver roleResolver;

    public LlmConfigAccess(LlmConfigRoleResolver roleResolver) {
        this.roleResolver = roleResolver;
    }

    /**
     * 非法写操作时抛出 403. 用于控制器写端点开头.
     */
    public void assertWriteOperator() {
        if (roleResolver.currentRole() != LlmRole.OPERATOR) {
            throw GatewayException.forbidden("仅运营权限可修改 LLM 配置, 当前角色: "
                    + roleResolver.currentRole());
        }
    }

    /** 当前角色是否为运营. */
    public boolean isOperator() {
        return roleResolver.currentRole() == LlmRole.OPERATOR;
    }
}