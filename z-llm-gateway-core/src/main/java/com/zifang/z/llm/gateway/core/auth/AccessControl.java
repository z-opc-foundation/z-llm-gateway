package com.zifang.z.llm.gateway.core.auth;

import com.zifang.z.llm.gateway.api.dto.ApiKey;
import com.zifang.z.llm.gateway.api.dto.Vendor;
import com.zifang.z.llm.gateway.api.exception.GatewayException;
import com.zifang.z.llm.gateway.core.properties.GatewayProperties;
import com.zifang.z.llm.gateway.core.router.ModelRouter;
import com.zifang.z.llm.gateway.core.usage.UsageLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * 授权边界 — ApiKey 白名单与请求配额上限的强制点.
 *
 * <p>ApiKey 早就有 allowedModels / allowedVendors 两个字段, 但此前没有任何一处代码读它,
 * 于是"只允许调用 qwen 的 key"照样能打通 openai, 属于越权. 本类是这条边界的唯一入口.
 *
 * <p>{@code z.llm.enforce-key-restrictions=false} 时只告警不拦, 用于灰度上线.
 */
public class AccessControl {

    private static final Logger log = LoggerFactory.getLogger(AccessControl.class);

    private final GatewayProperties properties;

    /** 用量台账 (可选): 有它才做预付额度扣减校验; 无它(如单测)跳过额度闸. */
    private final UsageLedger usageLedger;

    public AccessControl(GatewayProperties properties) {
        this(properties, null);
    }

    public AccessControl(GatewayProperties properties, UsageLedger usageLedger) {
        this.properties = properties;
        this.usageLedger = usageLedger;
    }

    public void authorize(ApiKey key, ModelRouter.Resolved resolved, Integer requestedMaxTokens) {
        if (key == null) {
            throw GatewayException.unauthorized("Missing API key");
        }
        checkMaxTokens(requestedMaxTokens);
        // 预付额度闸 — 与 enforce-key-restrictions 无关: 余额用尽即使是"白名单也放行"的灰度
        // 也必须拒绝, 否则对外售卖就是白送.
        checkQuota(key);
        if (!properties.isEnforceKeyRestrictions()) {
            return;
        }
        List<Vendor> allowedVendors = key.getAllowedVendors();
        if (allowedVendors != null && !allowedVendors.isEmpty()
                && !allowedVendors.contains(resolved.vendor())) {
            deny(key, "vendor " + resolved.vendor().code() + " is not allowed for this api key");
        }
        List<String> allowedModels = key.getAllowedModels();
        if (allowedModels != null && !allowedModels.isEmpty()
                && !matchesAny(allowedModels, resolved)) {
            deny(key, "model " + resolved.canonical() + " is not allowed for this api key");
        }
    }

    /** max_tokens 上限: 未传视为不主动限制 (由 vendor 决定), 传了超限直接拒. */
    private void checkMaxTokens(Integer requested) {
        Integer cap = properties.getMaxTokensLimit();
        if (requested == null || cap == null || cap <= 0) {
            return;
        }
        if (requested > cap) {
            throw GatewayException.invalidRequest(
                    "max_tokens " + requested + " exceeds gateway limit " + cap);
        }
    }

    /**
     * 预付额度闸: 该 key 的累计花费 (&gt;= quota) 时拒绝, 返回 402.
     *
     * <p>额度按"累计花费"语义 (UsageLedger 按 key 记 cost), 用前校验、用后由 ledger 累计扣减。
     * {@code quota} 为 null 视为不限; 无账本时跳过 (单测/无计量部署).
     */
    private void checkQuota(ApiKey key) {
        if (usageLedger == null || key.getQuota() == null) {
            return;
        }
        double used = usageLedger.totalCost(key);
        if (used >= key.getQuota()) {
            if (log.isInfoEnabled()) {
                log.info("quota exhausted for key {} ({}): used={} quota={}",
                        key.getId(), masked(key), used, key.getQuota());
            }
            throw GatewayException.quotaExceeded(
                    "quota exhausted: used " + used + " / quota " + key.getQuota());
        }
    }

    private void deny(ApiKey key, String reason) {
        if (log.isInfoEnabled()) {
            log.info("access denied for key {} ({}): {}", key.getId(), masked(key), reason);
        }
        throw GatewayException.forbidden(reason);
    }

    private static String masked(ApiKey key) {
        if (key.getMaskedKey() != null && !key.getMaskedKey().isEmpty()) {
            return key.getMaskedKey();
        }
        return "****";
    }

    /**
     * 白名单匹配: 支持 "openai/gpt-4o" 全量、"gpt-4o" 裸名、以及尾部通配 "gpt-4*" / "openai/*".
     */
    static boolean matchesAny(List<String> patterns, ModelRouter.Resolved resolved) {
        String canonical = resolved.canonical();
        String bare = resolved.bareModel();
        for (String raw : patterns) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            String p = raw.trim().toLowerCase(Locale.ROOT);
            if (p.equals(canonical.toLowerCase(Locale.ROOT)) || p.equals(bare.toLowerCase(Locale.ROOT))) {
                return true;
            }
            if (p.endsWith("*")) {
                String head = p.substring(0, p.length() - 1);
                if (canonical.toLowerCase(Locale.ROOT).startsWith(head)
                        || bare.toLowerCase(Locale.ROOT).startsWith(head)) {
                    return true;
                }
            }
        }
        return false;
    }
}
