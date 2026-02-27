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

        // 5. 체인 실행 후 DB 저장 로직을 메인 체인에 편입 (then 사용)
        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .then(Mono.defer(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    int status = (exchange.getResponse().getStatusCode() != null)
                            ? exchange.getResponse().getStatusCode().value() : 500;

                    // ⭐️ [핵심] DB 저장을 담당하는 내부 Mono 안에서만 onErrorResume을 처리하도록 위치를 변경!
                    return Mono.fromRunnable(() -> {
                                try {
                                    RequestLog logEntity = RequestLog.builder()
                                            .requestId(finalTraceId)
                                            .path(path)
                                            .method(method)
                                            .status(status)
                                            .latencyMs(duration)
                                            .timestamp(requestTime)
                                            .tokenStatus(tokenStatus)
                                            .policyVersion(policyVersion)
                                            .build();

                                    requestLogRepository.save(logEntity);
                                    log.info("type=RESPONSE traceId={} status={} latency={}ms", finalTraceId, status, duration);
                                } catch (Exception e) {
                                    // DB 저장 중 발생한 동기적 예외 처리
                                    log.error("type=ERROR traceId={} msg={}", finalTraceId, e.getMessage());
                                    throw e; // 리액티브 체인으로 에러 전파
                                }
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .then() // Mono<Void> 변환
                            // 👇 메인 체인이 아닌, 'DB 저장 Mono' 전용 에러 처리
                            .onErrorResume(e -> {
                                log.error("type=LOG_SAVE_ERROR traceId={} msg={}", finalTraceId, e.getMessage());
                                return Mono.empty(); // DB 에러만 무시하고 메인 체인은 정상 종료
                            });
                }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}