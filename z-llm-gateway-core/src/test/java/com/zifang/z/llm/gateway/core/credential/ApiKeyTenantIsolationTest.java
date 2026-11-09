package com.zifang.z.llm.gateway.core.credential;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import com.zifang.z.llm.gateway.core.support.TestFixtures;
import com.zifang.z.llm.gateway.core.usage.UsageLedger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 租户隔离: 绑定 tenantCode 的 key 在请求 Tenant-Code 不一致时必须被拒 (403).
 *
 * <p>隔离强制点在 {@link ApiKeyService#enforceTenant}, 由 AuthorizationExtractor 在
 * 鉴权拿到 key 后调用. 平台级 key (未绑定租户) 与未开启隔离时不拦截 (向后兼容).
 */
public class ApiKeyTenantIsolationTest {

    private ApiKeyService service(boolean isolation) {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "upstream", 1));
        p.setTenantIsolationEnabled(isolation);
        return new ApiKeyService(p);
    }

    private ApiKey key(String tenant) {
        ApiKey k = new ApiKey();
        k.setId("ak-test");
        k.setKey("sk-tenant-a");
        k.setStatus("active");
        k.setTenantCode(tenant);
        return k;
    }

    /** 未开启隔离时, 即使租户不匹配也放行 (向后兼容). */
    @Test
    public void isolationDisabledAllowsAnyTenant() {
        ApiKeyService service = service(false);
        ApiKey k = key("tenantA");
        assertSame(k, service.enforceTenant("tenantB", k));
    }

    /** 平台级 key (未绑定租户) 开启隔离后仍放行. */
    @Test
    public void platformKeyBypassesTenantCheck() {
        ApiKeyService service = service(true);
        ApiKey k = key(null);
        assertSame(k, service.enforceTenant("whatever", k));
    }

    /** 租户一致: 放行. */
    @Test
    public void matchingTenantAllowed() {
        ApiKeyService service = service(true);
        ApiKey k = key("tenantA");
        assertSame(k, service.enforceTenant("tenantA", k));
    }

    /** 请求未带 Tenant-Code 头: 绑定租户的 key 必须被拒. */
    @Test
    public void missingTenantHeaderRejected() {
        ApiKeyService service = service(true);
        ApiKey k = key("tenantA");
        try {
            service.enforceTenant((String) null, k);
            fail("must reject when Tenant-Code header missing");
        } catch (GatewayException e) {
            assertEquals(403, e.getHttpStatus());
        }
    }

    /** 租户不一致: 403 forbidden. */
    @Test
    public void mismatchedTenantRejected() {
        ApiKeyService service = service(true);
        ApiKey k = key("tenantA");
        try {
            service.enforceTenant("tenantB", k);
            fail("must reject mismatched tenant");
        } catch (GatewayException e) {
            assertEquals(403, e.getHttpStatus());
        }
    }

    /** 大小写不敏感 (租户编码视为不区分大小写). */
    @Test
    public void tenantMatchIsCaseInsensitive() {
        ApiKeyService service = service(true);
        ApiKey k = key("tenantA");
        assertSame(k, service.enforceTenant("TENANTA", k));
    }

    /** 租户隔离关闭时 allowedForTenant 恒 true. */
    @Test
    public void allowedForTenantWhenDisabled() {
        ApiKeyService service = service(false);
        assertTrue(service.allowedForTenant("tenantA", key("tenantA")));
        assertTrue(service.allowedForTenant("tenantB", key("tenantA")));
    }

    /** 启用了隔离后, 未绑定租户的平台 key 对任何租户放行. */
    @Test
    public void platformKeyAnyTenantWhenEnabled() {
        ApiKeyService service = service(true);
        assertTrue(service.allowedForTenant("t1", key(null)));
        assertTrue(service.allowedForTenant("t2", key(null)));
    }

    /** issued key 保留租户绑定 (签发时传入 tenantCode 会一路带到生效表). */
    @Test
    public void issuedKeyKeepsTenantBinding() {
        ApiKeyService service = service(true);
        ApiKey tpl = new ApiKey();
        tpl.setName("tenant-key");
        tpl.setTenantCode("tenantA");
        ApiKey issued = service.issue(tpl);
        assertEquals("tenantA", issued.getTenantCode());
        // 用签发后的明文可以鉴权, 且租户匹配放行、不匹配拒绝
        assertSame(issued, service.enforceTenant("tenantA", issued));
        try {
            service.enforceTenant("tenantB", issued);
            fail("issued tenant-bound key must reject other tenant");
        } catch (GatewayException e) {
            assertEquals(403, e.getHttpStatus());
        }
    }

    /** UsageLedger 按租户归账: 绑定租户的 key 记录后, 可按租户对账. */
    @Test
    public void usageLedgerAggregatesByTenant() {
        GatewayProperties p = TestFixtures.properties(
                TestFixtures.credential(Vendor.OPENAI, "upstream", 1));
        p.getPricing().computeIfAbsent("openai/gpt-4o", k -> new GatewayProperties.Price(10d, 20d));
        UsageLedger ledger = new UsageLedger(p);

        ApiKey a = key("tenantA");
        a.setId("ak1");
        ApiKey b = key("tenantB");
        b.setId("ak2");

        ledger.record(a, Vendor.OPENAI, "gpt-4o", 1_000_000L, 500_000L);
        ledger.record(b, Vendor.OPENAI, "gpt-4o", 1_000_000L, 500_000L);

        // tenantA: 1M prompt*10 + 0.5M completion*20 = 10 + 10 = 20; tenantB 同
        assertEquals(20d, ledger.totalCostByTenant("tenantA"), 1e-6);
        assertEquals(20d, ledger.totalCostByTenant("tenantB"), 1e-6);
        assertEquals(0d, ledger.totalCostByTenant("tenantC"), 1e-6);
        assertEquals(20d, (Double) ledger.snapshotByTenant("tenantA").get("cost"), 1e-6);
        assertTrue(ledger.snapshot().get("byTenant") instanceof java.util.List);
        assertEquals(2, ((java.util.List<?>) ledger.snapshot().get("byTenant")).size());
    }
}