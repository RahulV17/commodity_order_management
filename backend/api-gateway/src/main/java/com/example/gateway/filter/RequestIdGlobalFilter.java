package com.example.gateway.filter;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Adds a correlation id to every request that crosses the gateway and logs
 * method, path, status and duration. The id is forwarded downstream so logs from
 * order-service, instrument-service and the gateway can be correlated.
 */
@Component
public class RequestIdGlobalFilter implements GlobalFilter, Ordered {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(RequestIdGlobalFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = request.getHeaders().getFirst(REQUEST_ID_HEADER);
        if (!StringUtils.hasText(requestId)) {
            requestId = UUID.randomUUID().toString();
        }

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(request.mutate().header(REQUEST_ID_HEADER, requestId).build())
                .build();
        mutatedExchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        long startedAt = System.currentTimeMillis();
        String method = request.getMethod().name();
        String path = request.getURI().getPath();
        String finalRequestId = requestId;

        return chain.filter(mutatedExchange)
                .doFinally(signal -> log.info("requestId={} {} {} -> {} ({} ms)",
                        finalRequestId,
                        method,
                        path,
                        mutatedExchange.getResponse().getStatusCode(),
                        System.currentTimeMillis() - startedAt));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
