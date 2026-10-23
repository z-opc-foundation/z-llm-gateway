package com.zifang.z.llm.gateway.core.credential;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.core.support.TestFixtures;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 平台 AK 桥接的运行期注册契约: AK 管理页建的 sk-* 密钥要能直接调网关,
 * 且配置 reload 不得把运行期注册的 key 冲掉.
 */
public class ApiKeyServiceRuntimeTest {

    private ApiKeyService newService() {
        return new ApiKeyService(TestFixtures.properties(
                TestFixtures.credential(com.zifang.z.llm.gateway.api.dto.Vendor.OPENAI, "upstream", 1)));
    }

    @Test
    public void registeredKeyAuthenticatesImmediately() {
        ApiKeyService service = newService();
        ApiKey k = new ApiKey();
        k.setId("platform-ak:5");
        k.setName("mine");
        k.setKey("sk-runtime-1");
        k.setStatus("active");
        assertTrue(service.register(k));
        assertNotNull("runtime key must authenticate", service.authenticate("sk-runtime-1"));
        assertEquals("platform-ak:5", service.authenticate("Bearer sk-runtime-1").getId());
    }

    @Test
    public void unregisterRemovesTheKey() {
        ApiKeyService service = newService();
        ApiKey k = new ApiKey();
        k.setKey("sk-runtime-2");
        k.setStatus("active");
        service.register(k);
        assertTrue(service.unregister("sk-runtime-2"));
        assertNull(service.authenticate("sk-runtime-2"));
        assertFalse(service.unregister("sk-runtime-2"));
    }

    /** 配置 reload 只管配置面; 平台 AK 不许被冲掉, 否则 AK 管理页建的 key 全体失效. */
    @Test
    public void configReloadKeepsRuntimeKeys() {
        ApiKeyService service = newService();
        ApiKey k = new ApiKey();
        k.setKey("sk-runtime-3");
        k.setStatus("active");
        service.register(k);
        service.reload();
        assertNotNull("runtime key must survive config reload", service.authenticate("sk-runtime-3"));
    }

    /** 停用状态不允许注册, 且要把已在网关里的旧条目一并注销. */
    @Test
    public void registeringDisabledKeyRevokesIt() {
        ApiKeyService service = newService();
        ApiKey k = new ApiKey();
        k.setKey("sk-runtime-4");
        k.setStatus("active");
        service.register(k);
        assertNotNull(service.authenticate("sk-runtime-4"));

        ApiKey disabled = new ApiKey();
        disabled.setKey("sk-runtime-4");
        disabled.setStatus("disabled");
        assertFalse(service.register(disabled));
        assertNull(service.authenticate("sk-runtime-4"));
    }
}
