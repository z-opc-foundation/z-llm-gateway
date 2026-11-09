package com.zifang.z.llm.gateway.config.auth;

import org.springframework.stereotype.Component;

/**
 * 默认角色解析器 — 管理面调用视为平台运营.
 *
 * <p>覆盖预设: 当宿主的访问控制尚未接入时, 保证已有写端点不因角色未知而误伤;
 * 需要真正的 用户/运营 权限区分时, 由部署方提供一个 {@link LlmConfigRoleResolver} bean
 * (本实现带 @ConditionalOnMissingBean 语义, 被覆盖后即失效).
 */
@Component
public class DefaultLlmConfigRoleResolver implements LlmConfigRoleResolver {

    @Override
    public LlmRole currentRole() {
        return LlmRole.OPERATOR;
    }
}