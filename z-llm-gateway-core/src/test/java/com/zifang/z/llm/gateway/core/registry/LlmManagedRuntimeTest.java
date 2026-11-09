package com.zifang.z.llm.gateway.core.registry;

import com.zifang.z.llm.gateway.api.dto.LlmCredential;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import com.zifang.z.llm.gateway.core.router.ModelRouter;
import com.zifang.z.llm.gateway.core.support.TestFixtures;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 配置面(DB) → 运行时 的桥: LlmManagedRuntime 把受管凭据并入凭据池并重建 provider,
 * 使 ModelRouter 立即能路由到前端新增的模型 —— 修复"UI 配了模型但网关 model not found"。
 */
public class LlmManagedRuntimeTest {

    private LlmManagedRuntime runtimeWith(LlmCredentialStore store, LlmProviderRegistry registry) {
        return new LlmManagedRuntime(store, registry);
    }

    private LlmCredential dbCredential(String alias, Vendor vendor, String... allowedModels) {
        LlmCredential c = new LlmCredential();
        c.setAlias(alias);
        c.setVendor(vendor);
        c.setApiKey("sk-db-" + alias);
        c.setPriority(10);
        c.setStatus("active");
        List<String> models = new ArrayList<>();
        for (String m : allowedModels) {
            models.add(m);
        }
        c.setAllowedModels(models);
        return c;
    }

    @Test
    public void applyManaged_makesNewModelRoutable() {
        GatewayProperties props = new GatewayProperties();
        LlmCredentialStore store = TestFixtures.store(props);
        LlmProviderRegistry registry = TestFixtures.registry(store);
        ModelRouter router = new ModelRouter(registry, props);
        LlmManagedRuntime runtime = runtimeWith(store, registry);

        // 初始无任何凭据 ⇒ "deepseek-chat" 不可路由
        try {
            router.resolve("deepseek-chat");
            fail("未下发凭据时不应可路由");
        } catch (GatewayException expected) {
            // 预期 404
        }

        // 配置面下发一个 DB 管理的 DeepSeek 凭据
        runtime.applyManaged(List.of(dbCredential("ds-via-db", Vendor.DEEPSEEK, "deepseek-chat")));

        // 凭据进池
        assertEquals(1, store.findByVendor(Vendor.DEEPSEEK).size());
        // provider 实例被重建出来
        assertNotNull(registry.get(Vendor.DEEPSEEK, "ds-via-db"));
        // 请求可路由到该 vendor
        ModelRouter.Resolved r = router.resolve("deepseek-chat");
        assertEquals(Vendor.DEEPSEEK, r.vendor());
        assertEquals("ds-via-db", r.credentialAlias());
    }

    @Test
    public void applyManaged_managedOverridesSameAlias() {
        LlmCredential fileCred = TestFixtures.credential(Vendor.OPENAI, "shared", 1);
        GatewayProperties props = TestFixtures.properties(fileCred);
        LlmCredentialStore store = TestFixtures.store(props);
        LlmProviderRegistry registry = TestFixtures.registry(store);

        assertEquals("sk-test-shared", store.findByAlias("shared").getApiKey());

        LlmManagedRuntime runtime = runtimeWith(store, registry);
        LlmCredential dbCred = dbCredential("shared", Vendor.OPENAI, "gpt-4o");
        runtime.applyManaged(List.of(dbCred));

        // 受管凭据按 alias 覆盖静态配置
        assertEquals("sk-db-shared", store.findByAlias("shared").getApiKey());
        assertNotNull(registry.get(Vendor.OPENAI, "shared"));
    }

    @Test
    public void applyManaged_preservesFileCredential() {
        LlmCredential fileCred = TestFixtures.credential(Vendor.ANTHROPIC, "anthropic-primary", 1);
        LlmCredentialStore store = TestFixtures.store(TestFixtures.properties(fileCred));
        LlmProviderRegistry registry = TestFixtures.registry(store);

        LlmManagedRuntime runtime = runtimeWith(store, registry);
        runtime.applyManaged(List.of(dbCredential("ds-via-db", Vendor.DEEPSEEK)));

        // 静态凭据不被清掉, 新增的受管凭据并入
        assertEquals(1, store.findByVendor(Vendor.ANTHROPIC).size());
        assertEquals(1, store.findByVendor(Vendor.DEEPSEEK).size());
        assertTrue(registry.keys().size() >= 2);
    }

    @Test
    public void applyManaged_nullIsNoOp() {
        LlmCredential fileCred = TestFixtures.credential(Vendor.OPENAI, "o1", 1);
        LlmCredentialStore store = TestFixtures.store(TestFixtures.properties(fileCred));
        LlmProviderRegistry registry = TestFixtures.registry(store);
        LlmManagedRuntime runtime = runtimeWith(store, registry);

        runtime.applyManaged(null);

        assertEquals(1, store.size());
        assertNotNull(registry.get(Vendor.OPENAI, "o1"));
    }
}