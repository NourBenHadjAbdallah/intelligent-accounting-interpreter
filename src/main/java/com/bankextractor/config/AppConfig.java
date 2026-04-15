package com.bankextractor.config;

import com.bankextractor.model.ExtractionConfig;
import com.bankextractor.service.BankStatementService;
import com.bankextractor.export.ExportService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;

@Configuration
public class AppConfig {

    /**
     * Global extraction timeout (milliseconds).
     * The entire /api/extract pipeline must complete within this window.
     * 120 s is generous even for large, multi-page PDFs.
     * Adjust down if you want faster failure feedback.
     */
    private static final long EXTRACTION_TIMEOUT_MS = 120_000L;

    @Bean
    public ExtractionConfig extractionConfig() {
        return ExtractionConfig.builder()
                .classifyCategories(true)
                .validateBalances(false)
                .confidenceThreshold(0.25)
                .europeanFormat(true)
                .dateFormats(List.of(
                        "dd/MM/yy",
                        "dd/MM/yyyy",
                        "dd-MM-yyyy",
                        "dd.MM.yyyy",
                        "dd MMM yyyy",
                        "d MMM yyyy",
                        "MM/dd/yyyy",
                        "yyyy-MM-dd"
                ))
                .build();
    }

    @Bean
    public BankStatementService bankStatementService(ExtractionConfig config) {
        return new BankStatementService(config);
    }

    @Bean
    public ExportService exportService() {
        return new ExportService();
    }

    // ── CORS: allow Angular dev server to call the API ─────────────────────────
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOrigins("http://localhost:4200")
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false);
            }

            /**
             * Set the async timeout for MVC so that long-running extraction requests
             * are automatically cancelled at the servlet level after EXTRACTION_TIMEOUT_MS.
             * This is a safety net on top of the per-page timeouts in TabulaTableExtractor.
             */
            @Override
            public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setDefaultTimeout(EXTRACTION_TIMEOUT_MS);
            }
        };
    }

    /**
     * Servlet filter that enforces a hard wall-clock timeout on /api/extract* requests.
     *
     * If the extraction thread is still running after EXTRACTION_TIMEOUT_MS the filter
     * interrupts it and returns a 504 Gateway Timeout response so the Angular client
     * gets an actionable error instead of waiting indefinitely.
     */
    @Bean
    public FilterRegistrationBean<ExtractionTimeoutFilter> extractionTimeoutFilter() {
        FilterRegistrationBean<ExtractionTimeoutFilter> reg =
                new FilterRegistrationBean<>(new ExtractionTimeoutFilter(EXTRACTION_TIMEOUT_MS));
        reg.addUrlPatterns("/api/extract", "/api/extract/*");
        reg.setOrder(1);
        return reg;
    }

    /**
     * Timeout filter — wraps the request thread in a Future with a deadline.
     */
    static class ExtractionTimeoutFilter extends OncePerRequestFilter {

        private final long timeoutMs;
        private final ExecutorService exec =
                Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "extract-timeout-worker");
                    t.setDaemon(true);
                    return t;
                });

        ExtractionTimeoutFilter(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain)
                throws ServletException, IOException {

            // Wrap the downstream filter chain in a Future
            Future<?> future = exec.submit(() -> {
                try {
                    chain.doFilter(request, response);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                if (!response.isCommitted()) {
                    response.setStatus(HttpServletResponse.SC_GATEWAY_TIMEOUT);
                    response.setContentType("application/json");
                    response.getWriter().write(
                        "{\"error\":\"Extraction timed out after " +
                        (timeoutMs / 1000) + " seconds. " +
                        "Try the Manual extraction mode for complex PDFs.\"}");
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ServletException se) throw se;
                if (cause instanceof IOException ioe) throw ioe;
                throw new ServletException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                future.cancel(true);
            }
        }
    }
}