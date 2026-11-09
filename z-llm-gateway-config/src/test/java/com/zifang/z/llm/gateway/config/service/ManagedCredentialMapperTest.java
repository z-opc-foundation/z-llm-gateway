package com.zifang.z.llm.gateway.config.service;

import com.zifang.z.llm.gateway.api.dto.LlmCredential;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.config.entity.LlmModel;
import com.zifang.z.llm.gateway.config.entity.LlmProvider;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * DB 的 Provider/Model → 运行时 LlmCredential 的映射.
 */
public class ManagedCredentialMapperTest {

    private LlmProvider provider(String code, String type, Integer enabled) {
        LlmProvider p = new LlmProvider();
        p.setProviderCode(code);
        p.setProviderName(code);
        p.setProviderType(type);
        p.setBaseUrl("https://" + code + ".example.com/v1");
        p.setApiKey("ak-" + code);
        p.setEnabled(enabled);
        p.setPriority(10);
        return p;
    }

    private LlmModel model(String code, String providerCode, Integer enabled) {
        LlmModel m = new LlmModel();
        m.setModelCode(code);
        m.setModelName(code);
        m.setProviderCode(providerCode);
        m.setEnabled(enabled);
        return m;
    }

    @Test
    public void mapsProviderAndEnabledModels() {
        List<LlmProvider> providers = List.of(provider("deepseek-main", "deepseek", 1));
        List<LlmModel> models = Arrays.asList(
                model("deepseek-chat", "deepseek-main", 1),
                model("deepseek-reasoner", "deepseek-main", 1),
                model("deepseek-disabled-model", "deepseek-main", 0)); // disabled 不进白名单

        List<LlmCredential> out = ManagedCredentialMapper.toCredentials(providers, models);

        assertEquals(1, out.size());
        LlmCredential c = out.get(0);
        assertEquals("deepseek-main", c.getAlias());
        assertEquals(Vendor.DEEPSEEK, c.getVendor());
        assertEquals("ak-deepseek-main", c.getApiKey());
        assertEquals("active", c.getStatus());
        assertNotNull(c.getAllowedModels());
        assertEquals(2, c.getAllowedModels().size());
        assertTrue(c.getAllowedModels().contains("deepseek-chat"));
        assertTrue(c.getAllowedModels().contains("deepseek-reasoner"));
    }

    @Test
    public void disabledProviderHasDisabledStatus() {
        LlmProvider p = provider("openai-old", "openai", 0);
        List<LlmCredential> out = ManagedCredentialMapper.toCredentials(List.of(p), List.of());
        assertEquals(1, out.size());
        assertEquals("disabled", out.get(0).getStatus());
    }

    @Test
    public void unknownVendorIsSkipped() {
        LlmProvider p = provider("mystery", "mystery-vendor", 1);
        List<LlmCredential> out = ManagedCredentialMapper.toCredentials(List.of(p), List.of());
        assertTrue(out.isEmpty());
    }

    @Test
    public void resolveVendorIsCaseInsensitiveAndRejectsUnknown() {
        assertEquals(Vendor.OPENAI, ManagedCredentialMapper.resolveVendor("openai"));
        assertEquals(Vendor.QWEN, ManagedCredentialMapper.resolveVendor("QWEN"));
        assertNull(ManagedCredentialMapper.resolveVendor("not-a-vendor"));
        assertNull(ManagedCredentialMapper.resolveVendor(null));
    }

    @Test
    public void nullProviderListYieldsEmpty() {
        assertTrue(ManagedCredentialMapper.toCredentials(null, List.of()).isEmpty());
    }
}