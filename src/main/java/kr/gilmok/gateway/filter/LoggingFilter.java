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
        // 1. Trace ID 생성 (기존 값 있으면 유지, 없으면 새로 생성)
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }

        // 2. 요청 로그
        String path = exchange.getRequest().getURI().getPath();
        log.info("[{}] ➡️ Request: method={} path={}", traceId, exchange.getRequest().getMethod(), path);

        // 3. 뒷단 서버로 Trace ID 전달 (헤더 덮어쓰기 방식 적용)
        String finalTraceId = traceId;
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> httpHeaders.set("X-Trace-Id", finalTraceId)) // [수정됨] set으로 명확하게 교체
                .build();

        // 4. 응답 로그 (성공/실패 상관없이 무조건 실행)
        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .doFinally(signalType -> { // [수정됨] then -> doFinally로 변경
                    log.info("[{}] ⬅️ Response: status={} (signal={})",
                            finalTraceId,
                            exchange.getResponse().getStatusCode(),
                            signalType); // 정상(ON_COMPLETE)인지 에러(ON_ERROR)인지도 표시
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}