package kr.gilmok.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Component
public class LoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Trace ID 생성 (이미 있으면 그거 쓰고, 없으면 새로 발급)
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().substring(0, 8); // 짧게 8자리만
        }

        // 2. 요청 로그 찍기 (Gateway 진입)
        String path = exchange.getRequest().getURI().getPath();
        log.info("[{}] ➡️ Request: path={}", traceId, path);

        // 3. 뒷단 서버(API, Auth)로 Trace ID 전달
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .header("X-Trace-Id", traceId)
                .build();

        // 4. 응답 로그 찍기 (나갈 때)
        String finalTraceId = traceId;
        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .then(Mono.fromRunnable(() -> {
                    log.info("[{}] ⬅️ Response: status={}", finalTraceId, exchange.getResponse().getStatusCode());
                }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // 제일 먼저 실행되어야 함
    }
}