package com.zifang.z.llm.gateway.config.auth;

/**
 * 当前调用方角色解析器 — 由宿主 (z-opc 主壳) 按登录身份提供实现.
 *
 * <p>本模块不依赖任何鉴权框架; 部署方实现此接口, 在登录上下文中取出当前用户的角色.
 * 未提供实现时, 默认按{@link DefaultLlmConfigRoleResolver}处理 (管理面视为运营).
 */
public interface LlmConfigRoleResolver {

    /** 当前请求/调用方的角色. */
    LlmRole currentRole();
}