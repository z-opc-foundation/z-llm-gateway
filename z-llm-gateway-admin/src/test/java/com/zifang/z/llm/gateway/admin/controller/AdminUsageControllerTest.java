package com.zifang.z.llm.gateway.admin.controller;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.core.credential.ApiKeyService;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import com.zifang.z.llm.gateway.core.usage.UsageLedger;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 控制面 /usage 端点 — UsageLedger.snapshot() 终于有了 controller 调用点.
 */
public class AdminUsageControllerTest {

    @Test
    public void usage_returnsLedgerSnapshot() {
        GatewayProperties props = new GatewayProperties();
        UsageLedger ledger = new UsageLedger(props);

        ApiKey key = new ApiKey();
        key.setId("k-billing");
        key.setKey("sk-billing");
        ledger.record(key, Vendor.DEEPSEEK, "deepseek-chat", 1000, 200);

        // 只喂 usageLedger, 其它无关依赖置空 —— /usage 只读台账
        AdminController ctl = new AdminController(null, null, null, null, null, ledger);
        Map<String, Object> snap = ctl.usage();

        assertEquals("USD", snap.get("currency"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byKey = (List<Map<String, Object>>) snap.get("byKey");
        assertEquals(1, byKey.size());
        assertEquals("k-billing", byKey.get(0).get("keyId"));
        assertEquals(1000L, byKey.get(0).get("promptTokens"));
        assertEquals(200L, byKey.get(0).get("completionTokens"));
        assertEquals(1L, byKey.get(0).get("requests"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byKeyModel = (List<Map<String, Object>>) snap.get("byKeyModel");
        assertTrue(byKeyModel.size() >= 1);
        assertEquals("deepseek/deepseek-chat", byKeyModel.get(0).get("model"));
    }

    @Test
    public void usage_emptyLedgerIsEmptySnapshot() {
        AdminController ctl = new AdminController(null, null, null, null, null, new UsageLedger(new GatewayProperties()));
        Map<String, Object> snap = ctl.usage();
        assertEquals(0, ((List<?>) snap.get("byKey")).size());
    }

    @Test
    public void apiKeys_exposeQuotaUsedRemaining() {
        GatewayProperties props = new GatewayProperties();
        props.getPricing().put("deepseek/deepseek-chat", new GatewayProperties.Price(1.0, 2.0));
        ApiKey key = new ApiKey();
        key.setId("k-q");
        key.setKey("sk-q");
        key.setStatus("active");
        key.setQuota(5.0);
        props.setApiKeys(new ArrayList<>(Arrays.asList(key)));

        ApiKeyService svc = new ApiKeyService(props);
        svc.afterPropertiesSet();

        UsageLedger ledger = new UsageLedger(props);
        ledger.record(key, Vendor.DEEPSEEK, "deepseek-chat", 1_000_000, 0); // 花费 1.0

        AdminController ctl = new AdminController(null, null, null, svc, null, ledger);
        List<Map<String, Object>> rows = ctl.apiKeys();
        assertEquals(1, rows.size());
        assertEquals(5.0, rows.get(0).get("quota"));
        assertEquals(1.0, rows.get(0).get("used"));
        assertEquals(4.0, rows.get(0).get("remaining"));
    }

    @Test
    public void updateQuotaAndDisable_viaController() {
        GatewayProperties props = new GatewayProperties();
        ApiKey key = new ApiKey();
        key.setId("k-q");
        key.setKey("sk-q");
        key.setStatus("active");
        key.setQuota(10.0);
        props.setApiKeys(new ArrayList<>(Arrays.asList(key)));
        ApiKeyService svc = new ApiKeyService(props);
        svc.afterPropertiesSet();

        AdminController ctl = new AdminController(null, null, null, svc, null, new UsageLedger(props));

        // delta 充值 +5 => 15
        Map<String, Object> afterTopUp = ctl.updateQuota("k-q", Map.of("delta", 5.0));
        assertEquals(15.0, afterTopUp.get("quota"));
        assertEquals(15.0, svc.findByKeyId("k-q").getQuota(), 1e-9);

        // 绝对值设额 => 8
        Map<String, Object> afterSet = ctl.updateQuota("k-q", Map.of("quota", 8.0));
        assertEquals(8.0, afterSet.get("quota"));
        assertEquals(8.0, svc.findByKeyId("k-q").getQuota(), 1e-9);

        // 停用后鉴权失败
        Map<String, Object> disabled = ctl.disableKey("k-q");
        assertEquals("disabled", disabled.get("status"));
        assertEquals(null, svc.authenticate("sk-q"));
    }

    @Test
    public void issueKey_returnsFullKeyOnceAndRegisters() {
        GatewayProperties props = new GatewayProperties();
        ApiKeyService svc = new ApiKeyService(props);
        svc.afterPropertiesSet();
        AdminController ctl = new AdminController(null, null, null, svc, null, new UsageLedger(props));

        Map<String, Object> body = new HashMap<>();
        body.put("name", "外部客户 A");
        body.put("quota", 30.0);
        body.put("allowedVendors", Arrays.asList("openai", "deepseek"));
        body.put("expiresInDays", 90);

        Map<String, Object> out = ctl.issueKey(body);
        String full = (String) out.get("key");
        assertNotNull("签发应返回明文 key", full);
        assertTrue("明文应带 sk-zopc- 前缀", full.startsWith("sk-zopc-"));
        assertEquals(30.0, out.get("quota"));
        assertNotNull(out.get("expiresAt"));

        // 签发即注册: 立即可鉴权
        assertEquals(out.get("id"), svc.authenticate(full).getId());

        // 列表只回掩码, 不再含明文 key
        List<Map<String, Object>> rows = ctl.apiKeys();
        assertEquals(1, rows.size());
        assertFalse("列表不应带明文 key", rows.get(0).containsKey("key"));
        assertTrue(rows.get(0).containsKey("maskedKey"));
        assertFalse("掩码不应等于明文", rows.get(0).get("maskedKey").equals(full));
    }
}