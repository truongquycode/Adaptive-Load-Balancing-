package com.truongquycode.registrationservicealb.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class RegistrationServiceMetricsFilter extends OncePerRequestFilter {

    private final AtomicInteger inflightRequests = new AtomicInteger(0);

    public RegistrationServiceMetricsFilter(MeterRegistry registry) {
        Gauge.builder("http.server.requests.inflight", inflightRequests, AtomicInteger::get)
             .description("Số lượng HTTP requests đang được xử lý song song")
             .register(registry);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            inflightRequests.incrementAndGet();
            filterChain.doFilter(request, response);
        } finally {
            inflightRequests.decrementAndGet();
        }
    }
}
