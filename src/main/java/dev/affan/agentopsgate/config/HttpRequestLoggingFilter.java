package dev.affan.agentopsgate.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HttpRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);
    private final MeterRegistry meterRegistry;

    public HttpRequestLoggingFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        long started = LOGGER.isInfoEnabled() ? System.nanoTime() : 0L;
        try {
            filterChain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            meterRegistry.counter("gate.http.responses", "status_class", status / 100 + "xx").increment();
            if (LOGGER.isInfoEnabled()) {
                long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                LOGGER.info(
                        "event=http_request method={} path={} status={} duration_ms={}",
                        request.getMethod(),
                        request.getRequestURI(),
                        status,
                        durationMillis);
            }
        }
    }
}
