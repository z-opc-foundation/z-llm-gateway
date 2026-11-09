package com.zifang.z.llm.gateway.core.registry;

import com.zifang.z.llm.gateway.api.dto.LlmCredential;

import java.util.List;

/**
 * 受管运行时入口 — 配置面 (DB) 把凭据下发进网关运行时的唯一通道.
 *
 * <p>配置模块 ({@code z-llm-config}) 在 Provider / Model 落库后, 把 DB 里的供应商与模型
 * 翻译成 {@link LlmCredential} 列表, 通过本类推入运行时: {@link LlmCredentialStore} 合并凭据,
 * {@link LlmProviderRegistry} 重建 provider 缓存. 两者都完成后再进来的请求, ModelRouter
 * 就能命中这颗新建的 provider 及其模型 —— 解决了"前端配了模型但网关路由不到"的断层.
 *
 * <p>只有同时引入了 {@code z-llm-core} 运行时 (z.llm.enabled=true) 与 {@code z-llm-config}
 * 的部署才有意义; 纯配置面板场景下该 bean 不存在, 配置模块对此为可选注入 (no-op).
 */
public class LlmManagedRuntime {

    private final LlmCredentialStore credentialStore;
    private final LlmProviderRegistry providerRegistry;

    public LlmManagedRuntime(LlmCredentialStore credentialStore,
                             LlmProviderRegistry providerRegistry) {
        this.credentialStore = credentialStore;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 应用一批受管凭据并重建 provider 缓存.
     *
     * <p>先合并进凭据池 (按 alias 覆盖静态配置), 再 rebuild 注册表, 使新凭据
     * 立即生成对应的 provider 实例供路由使用.
     */
    public void applyManaged(List<LlmCredential> managed) {
        credentialStore.applyManaged(managed);
        providerRegistry.rebuild();
    }
}