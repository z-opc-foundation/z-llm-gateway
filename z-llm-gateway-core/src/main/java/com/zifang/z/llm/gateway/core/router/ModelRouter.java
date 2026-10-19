package com.zifang.z.llm.gateway.core.router;

import com.zifang.z.agent.kernel.llm.LlmProvider;
import com.zifang.z.agent.kernel.llm.Model;
import com.zifang.z.llm.gateway.api.dto.LlmCredential;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import com.zifang.z.llm.gateway.core.registry.LlmProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 模型路由 — 把请求里的 model 字符串解析成 (vendor, 裸 model id).
 *
 * <p>解决的三类真实输入:
 * <ul>
 *   <li>{@code "openai/gpt-4o"} — 显式 vendor 前缀.</li>
 *   <li>{@code "gpt-4o"} — OpenAI 官方 SDK / 绝大多数客户端只会发裸名, 不带前缀.</li>
 *   <li>{@code "meta-llama/Llama-3.1-70B"} — 名字本身就含斜杠的三方 model id,
 *       其前缀不是任何 vendor, 必须按 provider 声明的 supportsModel 判定而非按前缀切.</li>
 * </ul>
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    /** 解析结果. */
    public static final class Resolved {
        private final Vendor vendor;
        private final String bareModel;
        private final String requested;
        /**
         * 本次请求锁定的凭据别名.
         *
         * <p>null = 不锁定, 凭据池按该 vendor 全量参与 failover (老行为)。
         * 非 null 时 failover 池收窄到这一条 —— 同 vendor 挂多个 OpenAI 兼容上游时,
         * 换到另一条就等于把请求发给了另一个后端。
         */
        private final String credentialAlias;

        public Resolved(Vendor vendor, String bareModel, String requested) {
            this(vendor, bareModel, requested, null);
        }

        public Resolved(Vendor vendor, String bareModel, String requested, String credentialAlias) {
            this.vendor = vendor;
            this.bareModel = bareModel;
            this.requested = requested;
            this.credentialAlias = credentialAlias;
        }

        public Vendor vendor() {
            return vendor;
        }

        public String bareModel() {
            return bareModel;
        }

        public String requested() {
            return requested;
        }

        /** 锁定到单条凭据时的 alias; null 表示未锁定. */
        public String credentialAlias() {
            return credentialAlias;
        }

        /** "vendor/model" 形式的规范化 id. */
        public String canonical() {
            return vendor.code() + "/" + bareModel;
        }
    }

    private final LlmProviderRegistry registry;
    private final GatewayProperties properties;

    public ModelRouter(LlmProviderRegistry registry, GatewayProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    public Resolved resolve(String requested) {
        if (requested == null || requested.trim().isEmpty()) {
            throw GatewayException.modelNotFound(String.valueOf(requested));
        }
        String model = requested.trim();

        String aliased = applyAlias(model);
        if (aliased != null) {
            model = aliased;
        }

        int slash = model.indexOf('/');
        if (slash > 0) {
            Vendor byPrefix = vendorByPrefix(model.substring(0, slash));
            if (byPrefix != null) {
                String bare = model.substring(slash + 1);
                if (bare.isEmpty()) {
                    throw GatewayException.modelNotFound(requested);
                }
                return new Resolved(byPrefix, bare, requested, selectAlias(byPrefix, bare, requested));
            }
            // 前缀不是 vendor ⇒ 整个字符串可能就是裸 model id (deepseek 风格 / HF 风格命名).
            Resolved byCapability = resolveByCapability(model, model, requested);
            if (byCapability != null) {
                return byCapability;
            }
            throw GatewayException.modelNotFound(requested);
        }

        Resolved byCapability = resolveByCapability(model, model, requested);
        if (byCapability != null) {
            return byCapability;
        }
        throw GatewayException.modelNotFound(requested);
    }

    /** 列出网关当前可路由的模型卡 (供 /v1/models). */
    public Map<String, ModelCard> availableModels() {
        Map<String, ModelCard> out = new LinkedHashMap<>();
        for (Vendor v : Vendor.values()) {
            for (LlmProvider p : registry.listByVendor(v)) {
                if (p == null) {
                    continue;
                }
                for (Model m : p.listModels()) {
                    out.put(v.code() + "/" + m.getId(), new ModelCard(v, m));
                }
            }
        }
        return out;
    }

    // ---- 私有 ----

    private String applyAlias(String model) {
        Map<String, String> aliases = properties.getModelAliases();
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        String hit = aliases.get(model);
        if (hit == null) {
            hit = aliases.get(model.toLowerCase(Locale.ROOT));
        }
        if (hit != null) {
            log.debug("model alias {} -> {}", model, hit);
        }
        return hit;
    }

    private static Vendor vendorByPrefix(String prefix) {
        for (Vendor v : Vendor.values()) {
            if (v.code().equalsIgnoreCase(prefix)) {
                return v;
            }
        }
        return null;
    }

    /**
     * 按 provider 自己声明的 supportsModel 判定归属.
     * 命中唯一 vendor 时返回; 多个 vendor 都能claim 时按凭据 priority 取第一个 (确定性).
     *
     * <p>遍历的是「凭据」而不是 vendor: 同一 vendor 下两条 OpenAI 兼容上游里,
     * 只有 {@code allowedModels} 覆盖该 model 的那条才算数, 否则 capability 判定
     * 会把两条上游的模型清单混为一谈。
     */
    private Resolved resolveByCapability(String probe, String bareIfHit, String requested) {
        List<Vendor> hits = new ArrayList<>();
        for (Vendor v : Vendor.values()) {
            for (LlmCredential cred : registry.credentialsFor(v, probe)) {
                LlmProvider p = registry.get(v, cred.getAlias());
                if (p == null || !safeSupports(p, probe)) {
                    continue;
                }
                hits.add(v);
                break;
            }
        }
        if (hits.isEmpty()) {
            return null;
        }
        if (hits.size() > 1) {
            log.info("model {} claimed by {} vendors {}, routed to {}",
                    requested, hits.size(), hits, hits.get(0));
        }
        Vendor chosen = hits.get(0);
        String bare = bareIfHit;
        if (chosen == Vendor.QWEN && bare.startsWith("qwen/") && bare.length() > 5) {
            bare = bare.substring(5);
        }
        // QWEN 的 qwen/ 前缀已在上面剥掉, 白名单按裸名再判一次, 否则带前缀的名字匹配不上.
        return new Resolved(chosen, bare, requested, selectAlias(chosen, bare, requested));
    }

    private static boolean safeSupports(LlmProvider p, String modelId) {
        try {
            return p.supportsModel(modelId);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 选出本次请求该用的凭据别名, 并决定是否锁死 failover 池.
     *
     * <p>返回 null = 不锁, 该 vendor 全部凭据照旧参与 failover (同一上游的多个 AK 轮换)。
     * 返回非 null = 锁到这一条。
     *
     * <p>判据是「有没有凭据声明过白名单」: 白名单一旦出现, 候选之间的差别就不再是
     * AK 轮换而是不同的上游, 换凭据等于换后端, 因此锁死。全部未声明白名单时,
     * 它们仍是同一 vendor 的等价 AK, 保持原行为。
     */
    private String selectAlias(Vendor vendor, String bare, String requested) {
        List<LlmCredential> candidates = registry.credentialsFor(vendor, bare);
        if (candidates.isEmpty()) {
            // 分两种: 压根没凭据, 和有凭据但都声明不服务这个 model。后者要明说,
            // 否则配错 allowedModels 的人只会看到一句笼统的 model not found。
            String why = registry.hasCredential(vendor)
                    ? " (vendor " + vendor.code() + " 的凭据均未声明服务该 model)"
                    : "";
            throw GatewayException.modelNotFound(requested + why);
        }
        boolean anyRestricted = false;
        for (LlmCredential c : candidates) {
            List<String> allowed = c.getAllowedModels();
            if (allowed != null && !allowed.isEmpty()) {
                anyRestricted = true;
                break;
            }
        }
        return anyRestricted ? candidates.get(0).getAlias() : null;
    }

    /** 模型卡: vendor + kernel Model. */
    public static final class ModelCard {
        private final Vendor vendor;
        private final Model model;

        public ModelCard(Vendor vendor, Model model) {
            this.vendor = vendor;
            this.model = model;
        }

        public Vendor vendor() {
            return vendor;
        }

        public Model model() {
            return model;
        }
    }
}
