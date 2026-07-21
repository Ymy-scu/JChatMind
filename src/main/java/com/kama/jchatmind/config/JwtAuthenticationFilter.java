package com.kama.jchatmind.config;

import com.kama.jchatmind.util.JwtUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>Token 位置：{@code Authorization: Bearer <jwt>}，
 * 或（用于 {@link javax.servlet.SseListener SSE} 等无法带 header 的场景）
 * query {@code ?access_token=<jwt>}。</p>
 *
 * <p>解析出的身份会同时：</p>
 * <ul>
 *   <li>写入 {@link SecurityContextHolder}：{@code principal = userId}，
 *       为将来使用 {@code @AuthenticationPrincipal} 或
 *       {@code .anyRequest().authenticated()} 打好基础。</li>
 *   <li>写入 request attribute {@code userId / username}：
 *       兼容 {@code AuthController} 现有的 {@code @RequestAttribute("userId")} 用法，
 *       改动零破坏。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = extractToken(request);

            if (StringUtils.hasText(token) && jwtUtil.validateToken(token)) {
                String userId = jwtUtil.getUserIdFromToken(token);
                String username = jwtUtil.getUsernameFromToken(token);

                request.setAttribute("userId", userId);
                request.setAttribute("username", username);

                // 只在 context 为空时写入，避免覆盖上游/测试的 mock 认证
                if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    userId,
                                    null,
                                    List.of(new SimpleGrantedAuthority("ROLE_USER"))
                            );
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            }
        } catch (Exception e) {
            log.error("JWT 认证失败: {}", e.getMessage());
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // STATELESS 模式下，每次请求结束清 context，避免线程池复用泄漏身份
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Token 提取顺序：
     * <ol>
     *   <li>{@code Authorization: Bearer <jwt>}</li>
     *   <li>{@code ?access_token=<jwt>}（EventSource 等无法自定义 header 的场景）</li>
     * </ol>
     */
    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        String queryToken = request.getParameter("access_token");
        if (StringUtils.hasText(queryToken)) {
            return queryToken;
        }
        return null;
    }
}
