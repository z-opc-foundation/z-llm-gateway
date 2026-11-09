package com.zifang.z.llm.gateway.admin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 控制面鉴权过滤器 — 对所有 /z-llm/admin/** 生效.
 *
 * <p>令牌来源: {@code X-Admin-Token} 优先, 其次 {@code Authorization: Bearer <token>}.
 * 未启用令牌校验 (admin-token 为空) 时直接放行, 保持向后兼容.
 */
public class AdminTokenFilter extends OncePerRequestFilter {

    private final AdminTokenGuard guard;

    public AdminTokenFilter(AdminTokenGuard guard) {
        this.guard = guard;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 只拦控制面; 公网 /v1 数据面由 ApiKeyService 各自鉴权, 不经这里.
        return !request.getRequestURI().startsWith("/z-llm/admin");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = request.getHeader("X-Admin-Token");
        if (token == null) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring(7).trim();
            }
        }
        if (guard.authorize(token)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"admin token required\"}");
    }
}