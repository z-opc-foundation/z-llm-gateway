package com.zifang.z.llm.gateway.core.credential;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.core.credential.persist.JsonFileApiKeyPersistence;
import com.zifang.z.llm.gateway.core.credential.persist.NoopApiKeyPersistence;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 运行期 key 状态持久化 + 签发:
 * 已签发 key / 额度调整 / 停用配了 {@code JsonFileApiKeyPersistence} 时跨实例(重启)不丢.
 */
public class ApiKeyServicePersistTest {

    private static class Ctx implements AutoCloseable {
        Path file;
        GatewayProperties props = new GatewayProperties();

        Ctx() throws Exception {
            file = Files.createTempFile("zopc-akstore", ".json");
            Files.deleteIfExists(file);
            props.setApiKeyStorePath(file.toString());
        }

        JsonFileApiKeyPersistence persistence() {
            return new JsonFileApiKeyPersistence(file);
        }

        @Override
        public void close() throws Exception {
            Files.deleteIfExists(file);
        }
    }

    private ApiKeyService service(GatewayProperties props, Path file) {
        ApiKeyService svc = new ApiKeyService(props, new JsonFileApiKeyPersistence(file));
        svc.afterPropertiesSet();
        return svc;
    }

    private ApiKey cfgKey(String id) {
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setKey("sk-cfg-" + id);
        k.setStatus("active");
        k.setQuota(null);
        return k;
    }

    @Test
    public void issuedKeySurvivesRestart() throws Exception {
        try (Ctx c = new Ctx()) {
            ApiKeyService a = service(c.props, c.file);
            ApiKey issued = a.issue(tpl("生产网关", 50.0));

            // 新实例(模拟重启): 只给配置, 持久化自动回灌已签发 key
            ApiKeyService b = service(c.props, c.file);
            ApiKey restored = b.authenticate(issued.getKey());
            assertNotNull("重启后应按持久化的 secret 重建鉴权表", restored);
            assertEquals(issued.getId(), restored.getId());
            assertEquals(50.0, restored.getQuota(), 1e-9);
            assertEquals(issued.getId(), b.findByKeyId(issued.getId()).getId());
        }
    }

    @Test
    public void quotaAdjustmentSurvivesRestart() throws Exception {
        try (Ctx c = new Ctx()) {
            c.props.setApiKeys(new ArrayList<>(List.of(cfgKey("k1"))));
            ApiKeyService a = service(c.props, c.file);
            a.setQuota("k1", 88.0);

            ApiKeyService b = service(c.props, c.file);
            assertEquals("额度调整应跨重启保留", 88.0, b.findByKeyId("k1").getQuota(), 1e-9);
        }
    }

    @Test
    public void disableSurvivesRestart() throws Exception {
        try (Ctx c = new Ctx()) {
            c.props.setApiKeys(new ArrayList<>(List.of(cfgKey("k1"))));
            ApiKeyService a = service(c.props, c.file);
            assertNotNull(a.authenticate("sk-cfg-k1"));
            assertTrue(a.disable("k1"));

            ApiKeyService b = service(c.props, c.file);
            assertNull("停用应跨重启保留", b.authenticate("sk-cfg-k1"));
        }
    }

    @Test
    public void issueReturnsFullKeyAndRegistersImmediately() throws Exception {
        try (Ctx c = new Ctx()) {
            ApiKeyService a = service(c.props, c.file);
            ApiKey issued = a.issue(tpl(null, null));
            assertTrue("签发密钥应带 sk-zopc- 前缀", issued.getKey().startsWith("sk-zopc-"));
            assertEquals("active", issued.getStatus());
            assertNotNull(issued.getId());
            assertNotNull(issued.getCreatedAt());
            // 签发即注册: 立刻能鉴权
            assertEquals(issued.getId(), a.authenticate(issued.getKey()).getId());
            assertTrue(a.list().stream().anyMatch(k -> k.getId().equals(issued.getId())));
        }
    }

    @Test
    public void noopPersistenceWritesNothing() throws Exception {
        Path p = Files.createTempFile("zopc-nop", ".json");
        Files.deleteIfExists(p);
        try {
            GatewayProperties props = new GatewayProperties();
            ApiKeyService a = new ApiKeyService(props, new NoopApiKeyPersistence());
            a.afterPropertiesSet();
            ApiKey issued = a.issue(tpl(null, null));
            assertFalse("Noop 不落盘", Files.exists(p));
            assertNotNull(a.authenticate(issued.getKey()));
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    public void issueHonorsVendorAllowList() throws Exception {
        try (Ctx c = new Ctx()) {
            ApiKeyService a = service(c.props, c.file);
            ApiKey t = tpl(null, null);
            t.setAllowedVendors(Arrays.asList(Vendor.OPENAI));
            ApiKey issued = a.issue(t);
            assertEquals(Vendor.OPENAI, issued.getAllowedVendors().get(0));
        }
    }

    private ApiKey tpl(String name, Double quota) {
        ApiKey k = new ApiKey();
        k.setName(name);
        k.setQuota(quota);
        return k;
    }
}