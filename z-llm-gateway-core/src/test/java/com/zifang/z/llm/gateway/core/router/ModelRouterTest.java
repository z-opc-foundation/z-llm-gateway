package com.zifang.z.llm.gateway.core.router;

import com.zifang.z.llm.gateway.api.dto.LlmCredential;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import com.zifang.z.llm.gateway.core.registry.LlmCredentialStore;
import com.zifang.z.llm.gateway.core.registry.LlmProviderRegistry;
import com.zifang.z.llm.gateway.core.support.TestFixtures;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 模型路由: 前缀 / 裸名 / 名字自带斜杠 / 别名表 四种真实输入.
 */
public class ModelRouterTest {

    private ModelRouter routerFor(GatewayProperties props, LlmCredential... creds) {
        props.setModelAliases(props.getModelAliases());
        LlmCredentialStore store = TestFixtures.store(props);
        LlmProviderRegistry registry = TestFixtures.registry(store);
        return new ModelRouter(registry, props);
    }

    @Test
    public void explicitVendorPrefix() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        ModelRouter.Resolved r = routerFor(p,
                TestFixtures.credential(Vendor.OPENAI, "default", 1))
                .resolve("openai/gpt-4o");
        assertEquals(Vendor.OPENAI, r.vendor());
        assertEquals("gpt-4o", r.bareModel());
        assertEquals("openai/gpt-4o", r.canonical());
    }

    /** 真实 OpenAI SDK 只会发裸名, 此前这种输入直接 404. */
    @Test
    public void bareModelNameResolvesToClaimingVendor() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        ModelRouter.Resolved r = routerFor(p).resolve("gpt-4o");
        assertEquals(Vendor.OPENAI, r.vendor());
        assertEquals("gpt-4o", r.bareModel());
    }

    /**
     * 名字本身含斜杠的 model id 不能被当成 vendor 前缀切掉.
     */
    @Test
    public void slashBearingModelIdIsNotCutAsVendorPrefix() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.DEEPSEEK, "default", 1));
        ModelRouter router = routerFor(p);
        // deepseek provider 按前缀 claim "deepseek-" 系, "deepseek-chat" 命中.
        ModelRouter.Resolved r = router.resolve("deepseek-chat");
        assertEquals(Vendor.DEEPSEEK, r.vendor());
    }

    @Test
    public void aliasTableWins() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        p.setModelAliases(Collections.singletonMap("claude", "anthropic/claude-3-5-sonnet-latest"));
        LlmCredentialStore store = TestFixtures.store(TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1),
                TestFixtures.credential(Vendor.ANTHROPIC, "a", 1)));
        ModelRouter router = new ModelRouter(TestFixtures.registry(store), p);
        ModelRouter.Resolved r = router.resolve("claude");
        assertEquals(Vendor.ANTHROPIC, r.vendor());
        assertEquals("claude-3-5-sonnet-latest", r.bareModel());
    }

    @Test
    public void unknownModelGives404() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        try {
            routerFor(p).resolve("does-not-exist-xyz");
            fail("expected model_not_found");
        } catch (GatewayException e) {
            assertEquals(404, e.getHttpStatus());
        }
    }

    @Test
    public void nullAndEmptyModelRejected() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        ModelRouter router = routerFor(p);
        for (String bad : new String[]{null, "", "   "}) {
            try {
                router.resolve(bad);
                fail("expected 404 for [" + bad + "]");
            } catch (GatewayException e) {
                assertEquals(404, e.getHttpStatus());
            }
        }
    }

    /** vendor 前缀对但没配该 vendor 凭据 ⇒ 不能当成可路由目标. */
    @Test
    public void prefixWithoutCredentialIsNotFound() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        try {
            routerFor(p).resolve("gemini/gemini-1.5-pro");
            fail("expected 404");
        } catch (GatewayException e) {
            assertEquals(404, e.getHttpStatus());
        }
    }

    @Test
    public void availableModelsCarriesVendorNamespace() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "default", 1));
        ModelRouter router = routerFor(p);
        assertTrue("expected namespaced ids",
                router.availableModels().keySet().stream().allMatch(k -> k.startsWith("openai/")));
        assertTrue(router.availableModels().containsKey("openai/gpt-4o"));
    }

    // ---- 同 vendor 多上游: allowedModels 是唯一的区分手段 ----

    private static LlmCredential openaiUpstream(String alias, int priority, String... models) {
        LlmCredential c = TestFixtures.credential(Vendor.OPENAI, alias, priority);
        c.setAllowedModels(java.util.Arrays.asList(models));
        return c;
    }

    /**
     * 两个 OpenAI 兼容上游 (官方 + 本地代理) 共用 vendor=OPENAI 时,
     * 请求各自锁到声明服务该 model 的那一条凭据。
     */
    @Test
    public void twoOpenAiCompatibleUpstreamsAreSplitByAllowedModels() {
        LlmCredential official = openaiUpstream("openai-official", 1, "gpt-4o");
        LlmCredential local = openaiUpstream("openai-local", 2, "qwen3:30b-a3b");
        ModelRouter router = routerFor(TestFixtures.properties(official, local));

        assertEquals("openai-official",
                router.resolve("openai/gpt-4o").credentialAlias());
        assertEquals("openai-local",
                router.resolve("openai/qwen3:30b-a3b").credentialAlias());
    }

    /** priority 更低的那条若不服务该 model, 也不能抢走请求. */
    @Test
    public void lowerPriorityDoesNotStealAnotherUpstreamsModel() {
        LlmCredential official = openaiUpstream("openai-official", 1, "gpt-4o");
        LlmCredential local = openaiUpstream("openai-local", 2, "qwen3:30b-a3b");
        ModelRouter router = routerFor(TestFixtures.properties(official, local));
        ModelRouter.Resolved r = router.resolve("openai/qwen3:30b-a3b");
        assertEquals("openai-local", r.credentialAlias());
        assertEquals("qwen3:30b-a3b", r.bareModel());
    }

    /** 所有凭据都声明不服务该 model ⇒ 404, 且错因要写明是白名单不覆盖. */
    @Test
    public void modelOutsideEveryWhitelistIsNotFound() {
        LlmCredential official = openaiUpstream("openai-official", 1, "gpt-4o");
        LlmCredential local = openaiUpstream("openai-local", 2, "qwen3:30b-a3b");
        ModelRouter router = routerFor(TestFixtures.properties(official, local));
        try {
            router.resolve("openai/o3-mini");
            fail("expected 404");
        } catch (GatewayException e) {
            assertEquals(404, e.getHttpStatus());
            assertTrue("错因应指向 allowedModels, 实际: " + e.getMessage(),
                    e.getMessage().contains("allowedModels")
                            || e.getMessage().contains("allowed models")
                            || e.getMessage().contains("均未声明"));
        }
    }

    /**
     * 白名单一旦出现就把 failover 锁死; 全部未声明白名单时保持老行为 (多把 AK 轮换),
     * 否则这条修复会顺手废掉 ProviderInvoker 的跨 AK failover。
     */
    @Test
    public void whitelistLocksFailoverButPlainKeyPoolStaysRotating() {
        LlmCredential restricted = openaiUpstream("openai-local", 1, "qwen3:30b-a3b");
        LlmCredential unrestricted = TestFixtures.credential(Vendor.OPENAI, "openai-official", 2);
        assertEquals("openai-local",
                routerFor(TestFixtures.properties(restricted, unrestricted))
                        .resolve("openai/qwen3:30b-a3b").credentialAlias());

        LlmCredential a = TestFixtures.credential(Vendor.OPENAI, "ak-a", 1);
        LlmCredential b = TestFixtures.credential(Vendor.OPENAI, "ak-b", 2);
        assertNull("多把同上游 AK 不该被锁死",
                routerFor(TestFixtures.properties(a, b))
                        .resolve("openai/gpt-4o").credentialAlias());
    }
}
