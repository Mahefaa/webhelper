package com.example.demo.endpoint.security;

import com.example.demo.PojaGenerated;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;

@PojaGenerated
@Component
@Slf4j
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_ENV = "API_KEY";
    private static final String API_KEY_PARAM = "api_key";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        // Only protect our specific scraping endpoints to allow health checks to pass freely
        if (request.getRequestURI().startsWith("/horses")) {
            String systemKey = System.getenv(API_KEY_ENV);
            
            // Check headers first (preferred by VibeSec), fallback to query params for Google Sheets compatibility
            String requestKey = request.getHeader("x-api-key");
            if (requestKey == null || requestKey.isEmpty()) {
                requestKey = request.getParameter(API_KEY_PARAM);
            }

            // Defense in depth: fail closed if the server hasn't configured the key
            if (systemKey == null || systemKey.isEmpty()) {
                log.error("CRITICAL: Server API_KEY environment variable is missing.");
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Server security configuration error.");
                return;
            }

            // Constant-time comparison to prevent timing attacks
            if (requestKey == null || !MessageDigest.isEqual(systemKey.getBytes(), requestKey.getBytes())) {
                log.warn("Unauthorized access attempt to {}", request.getRequestURI());
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or missing API key.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }
}
