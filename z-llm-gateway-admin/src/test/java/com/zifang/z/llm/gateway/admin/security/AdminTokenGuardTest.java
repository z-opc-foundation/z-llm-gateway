package com.zifang.z.llm.gateway.admin.security;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 控制面管理令牌: 未配置时放行 (向后兼容); 配置后必须常量时间相等.
 */
public class AdminTokenGuardTest {

    @Test
    public void disabledWhenBlank_authorizeAlwaysTrue() {
        assertFalse(new AdminTokenGuard(null).enabled());
        assertFalse(new AdminTokenGuard("  ").enabled());
        assertTrue(new AdminTokenGuard(null).authorize("anything"));
        assertTrue(new AdminTokenGuard("   ").authorize(null));
    }

    @Test
    public void rejectWhenMultipleWrongBeforeMatch() {
        // 确认不是"先匹配到长度相等就放行"
        AdminTokenGuard guard = new AdminTokenGuard("secret-token");
        assertFalse(guard.authorize(null));
        assertFalse(guard.authorize(""));
        assertFalse(guard.authorize("secret"));
        assertFalse(guard.authorize("SECRET-TOKEN"));
        assertFalse(guard.authorize("secret-token-x"));
    }

    @Test
    public void exactMatchAuthorized() {
        AdminTokenGuard guard = new AdminTokenGuard("secret-token");
        assertTrue(guard.enabled());
        assertTrue(guard.authorize("secret-token"));
        // 容忍前后空白
        assertTrue(guard.authorize("  secret-token  "));
    }
}