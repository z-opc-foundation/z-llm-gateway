package com.zifang.z.llm.gateway.core.auth;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import com.zifang.z.llm.gateway.core.router.ModelRouter;
import com.zifang.z.llm.gateway.core.usage.UsageLedger;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * 预付额度闸 — 累计花费达到 quota 后拒绝 (402); 未配置额度/无账本时放行.
 */
public class AccessControlQuotaTest {

    private static final ModelRouter.Resolved DS =
            new ModelRouter.Resolved(Vendor.DEEPSEEK, "deepseek-chat", "deepseek-chat");

    private GatewayProperties propsWithPrice() {
        GatewayProperties p = new GatewayProperties();
        // 每百万 token: prompt 1.0, completion 2.0
        p.getPricing().put("deepseek/deepseek-chat", new GatewayProperties.Price(1.0, 2.0));
        return p;
    }

    private ApiKey key(String id, Double quota) {
        ApiKey k = new ApiKey();
        k.setId(id);
        k.setKey("sk-" + id);
        k.setStatus("active");
        k.setQuota(quota);
        return k;
    }

    @Test
    public void underQuotaPasses() {
        GatewayProperties p = propsWithPrice();
        UsageLedger ledger = new UsageLedger(p);
        ApiKey k = key("k1", 0.5);
        ledger.record(k, Vendor.DEEPSEEK, "deepseek-chat", 100_000, 0); // cost 0.1 < 0.5

        new AccessControl(p, ledger).authorize(k, DS, null); // 不抛
    }

    @Test
    public void atOrOverQuotaIsRejectedWith402() {
        GatewayProperties p = propsWithPrice();
        UsageLedger ledger = new UsageLedger(p);
        ApiKey k = key("k1", 0.5);
        ledger.record(k, Vendor.DEEPSEEK, "deepseek-chat", 600_000, 0); // cost 0.6 >= 0.5

        try {
            new AccessControl(p, ledger).authorize(k, DS, null);
            fail("超额应被拒");
        } catch (GatewayException e) {
            assertEquals(402, e.getHttpStatus());
            assertEquals("quota_exceeded", e.getCode());
        }
    }

    @Test
    public void nullQuotaIsUnlimited() {
        GatewayProperties p = propsWithPrice();
        UsageLedger ledger = new UsageLedger(p);
        ApiKey k = key("k1", null);
        ledger.record(k, Vendor.DEEPSEEK, "deepseek-chat", 10_000_000, 0); // 花很多

        new AccessControl(p, ledger).authorize(k, DS, null); // 不限额度 => 放行
    }

    @Test
    public void withoutLedgerQuotaIsNotEnforced_backwardCompatible() {
        GatewayProperties p = propsWithPrice();
        ApiKey k = key("k1", 0.001); // 极小额度
        // 1 参构造 (无账本), 老测试/老装配路径: 额度闸跳过, 不改变既有行为
        new AccessControl(p).authorize(k, DS, null);
    }
}