package com.zifang.z.llm.gateway.config.auth;

/**
 * LLM 配置面的权限角色.
 *
 * <p>用户 (USER) / 运营 (OPERATOR) 对应设计稿里的「身份切换」:
 * 同一套控制台, 运营能写配置, 普通用户只读. 网关自身的对外数据面 (/v1, baseurl+令牌)
 * 仍由 ApiKey 鉴权; 此角色只约束管理面 (config) 的写操作.
 */
public enum LlmRole {

    /** 普通用户/租户 — 只读配置与接口. */
    USER,

    /** 平台运营 — 可创建/更新/删除供应商、模型、工具模板. */
    OPERATOR
}