package com.zifang.z.llm.gateway.config.auth;

import com.zifang.z.llm.gateway.api.exception.GatewayException;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 管理面写操作 RBAC: 只有运营可写, 用户被拒 (403).
 */
public class LlmConfigAccessTest {

    private LlmConfigAccess accessWith(LlmRole role) {
        return new LlmConfigAccess(() -> role);
    }

    @Test
    public void operator_canWrite() {
        accessWith(LlmRole.OPERATOR).assertWriteOperator();
    }

    @Test(expected = GatewayException.class)
    public void user_isBlockedWith403() {
        LlmConfigAccess access = accessWith(LlmRole.USER);
        try {
            access.assertWriteOperator();
        } catch (GatewayException e) {
            org.junit.Assert.assertEquals(403, e.getHttpStatus());
            throw e;
        }
        fail("USER 不应能写");
    }

    @Test
    public void isOperator_reflectsRole() {
        assertTrue(accessWith(LlmRole.OPERATOR).isOperator());
        assertFalse(accessWith(LlmRole.USER).isOperator());
    }

    @Test
    public void defaultResolverIsOperator() {
        assertTrue(new DefaultLlmConfigRoleResolver().currentRole() == LlmRole.OPERATOR);
        // 无宿主认证时, 默认实现保证既有写端点不被误伤
        new LlmConfigAccess(new DefaultLlmConfigRoleResolver()).assertWriteOperator();
    }
}