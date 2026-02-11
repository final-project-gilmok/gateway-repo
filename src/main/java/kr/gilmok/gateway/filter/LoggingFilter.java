package kr.gilmok.gateway.filter;

import kr.gilmok.gateway.entity.RequestLog;
import kr.gilmok.gateway.repository.RequestLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingFilter implements GlobalFilter, Ordered {

    private final RequestLogRepository requestLogRepository;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Trace ID 생성
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }

        // 2. 요청 정보 추출 및 시각 기록
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        // [핵심 수정] 요청 시작 시각 미리 캡처 (응답 시각 X -> 요청 시각 O)
        LocalDateTime requestTime = LocalDateTime.now();

        String finalTraceId = traceId;
        String tokenStatus = "UNKNOWN"; // 추후 연동
        Integer policyVersion = 1;

        // 3. Trace ID 헤더 전달
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> httpHeaders.set("X-Trace-Id", finalTraceId))
                .build();

        // 4. 요청 로그 (Loki용 포맷)
        log.info("type=REQUEST traceId={} method={} path={}", finalTraceId, method, path);
        long startTime = System.currentTimeMillis();

        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .doFinally(signalType -> {
                    long duration = System.currentTimeMillis() - startTime;
                    int status = (exchange.getResponse().getStatusCode() != null)
                            ? exchange.getResponse().getStatusCode().value() : 500;

                    // [핵심 수정] 블로킹 I/O(DB 저장)를 별도 스레드(boundedElastic)로 격리
                    Mono.fromRunnable(() -> {
                                try {
                                    RequestLog logEntity = RequestLog.builder()
                                            .requestId(finalTraceId)
                                            .path(path)
                                            .method(method)
                                            .status(status)
                                            .latencyMs(duration)
                                            .timestamp(requestTime) // [핵심 수정] 아까 캡처한 요청 시각 사용
                                            .tokenStatus(tokenStatus)
                                            .policyVersion(policyVersion)
                                            .build();

                                    requestLogRepository.save(logEntity); // 여기서 블로킹 발생 -> 안전하게 격리됨

                                    log.info("type=RESPONSE traceId={} status={} latency={}ms", finalTraceId, status, duration);
                                } catch (Exception e) {
                                    log.error("type=ERROR traceId={} msg={}", finalTraceId, e.getMessage());
                                }
                            })
                            .subscribeOn(Schedulers.boundedElastic()) // [중요] Netty 스레드 보호
                            .subscribe();
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}