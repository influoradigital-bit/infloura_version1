package com.influora.security;

import com.influora.domain.enums.UserType;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                var claims = jwtService.parseAccessToken(token);
                String userId = claims.getSubject();
                String email = claims.get("email", String.class);
                String userTypeStr = claims.get("userType", String.class);
                String workspaceId = claims.get("workspaceId", String.class);
                UserType userType = UserType.valueOf(userTypeStr);
                AuthPrincipal principal = new AuthPrincipal(userId, email, userType, workspaceId);
                var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
