package com.zifang.z.llm.gateway.core.credential;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * 运行期额度管理: 改额/充值/停用都直接作用在生效实例上, 下一个请求立刻读到.
 */
public class ApiKeyServiceQuotaTest {

    private ApiKeyService serviceWith(ApiKey key) {
        GatewayProperties props = new GatewayProperties();
        props.setApiKeys(new ArrayList<>(Arrays.asList(key)));
        ApiKeyService svc = new ApiKeyService(props);
        svc.afterPropertiesSet();
        return svc;
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
    public void setQuotaMutatesTheLiveInstanceSeenByAuth() {
        ApiKey k = key("k1", 10.0);
        ApiKeyService svc = serviceWith(k);

        // 鉴权返回的就是生效表里的同一实例
        assertSame(k, svc.authenticate("sk-k1"));

        svc.setQuota("k1", 5.0);
        assertEquals(5.0, svc.findByKeyId("k1").getQuota(), 1e-9);
        // 鉴权拿到的对象也是 5.0 (AccessControl 额度校验读它)
        assertEquals(5.0, svc.authenticate("sk-k1").getQuota(), 1e-9);

        // 设为不限
        svc.setQuota("k1", null);
        assertNull(svc.findByKeyId("k1").getQuota());
    }

    @Test
    public void topUpAddsAndClampsToZero() {
        ApiKeyService svc = serviceWith(key("k1", 10.0));
        assertEquals(13.0, svc.topUp("k1", 3.0).getQuota(), 1e-9);
        assertEquals(0.0, svc.topUp("k1", -100.0).getQuota(), 1e-9); // 不会为负
    }

    @Test
    public void topUpFromUnlimitedStartsAtZero() {
        ApiKeyService svc = serviceWith(key("k1", null)); // 不限
        assertEquals(5.0, svc.topUp("k1", 5.0).getQuota(), 1e-9);
    }

    @Test
    public void disableRemovesFromAuth() {
        ApiKeyService svc = serviceWith(key("k1", 10.0));
        assertNotNull(svc.authenticate("sk-k1"));
        assertTrueDisable(svc.disable("k1"));
        assertNull(svc.authenticate("sk-k1"));
        assertNull(svc.findByKeyId("k1"));
    }

    @Test
    public void unknownKeyThrows404() {
        ApiKeyService svc = serviceWith(key("k1", 10.0));
        try {
            svc.setQuota("nope", 1.0);
            fail("未知 key 应 404");
        } catch (GatewayException e) {
            assertEquals(404, e.getHttpStatus());
            assertEquals("api_key_not_found", e.getCode());
        }
    }

    private static void assertTrueDisable(boolean b) {
        org.junit.Assert.assertTrue(b);
    }
}