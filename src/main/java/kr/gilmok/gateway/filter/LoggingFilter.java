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

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor // [중요] final 필드 생성자 자동 생성 (Repository 주입)
public class LoggingFilter implements GlobalFilter, Ordered {

    private final RequestLogRepository requestLogRepository;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Trace ID 생성 (기존 헤더 있으면 재사용, 없으면 신규 생성)
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }

        // 2. 요청 정보 미리 추출 (Lambda에서 쓰기 위해 final 변수처럼 취급)
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        // 3. 뒷단 서버로 Trace ID 전달 (헤더 덮어쓰기)
        String finalTraceId = traceId; // Lambda용 final 변수
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> httpHeaders.set("X-Trace-Id", finalTraceId))
                .build();

        // 4. 요청 로그 출력
        log.info("[{}] ➡️ Request: method={} path={}", finalTraceId, method, path);

        // 5. 실행 시간 측정 시작
        long startTime = System.currentTimeMillis();

        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .doFinally(signalType -> {
                    // 6. 응답 완료 시점 (성공/실패 무관하게 실행됨)
                    long duration = System.currentTimeMillis() - startTime;

                    // 응답 상태 코드 안전하게 가져오기 (null일 경우 500 처리)
                    int status = 500;
                    if (exchange.getResponse().getStatusCode() != null) {
                        status = exchange.getResponse().getStatusCode().value();
                    }

                    // 7. 로그 객체 생성 (Builder 사용)
                    RequestLog logEntity = RequestLog.builder()
                            .traceId(finalTraceId)
                            .path(path)
                            .method(method)
                            .status(status)
                            .latencyMs(duration)
                            .timestamp(LocalDateTime.now())
                            .build();

                    // 8. DB 저장 (비동기 스레드에서 실행됨)
                    try {
                        requestLogRepository.save(logEntity);
                        log.info("[{}] ⬅️ Response: status={} ({}ms)", finalTraceId, status, duration);
                    } catch (Exception e) {
                        log.error("[{}] ❌ Log Save Error: {}", finalTraceId, e.getMessage());
                    }
                });
    }

    @Override
    public int getOrder() {
        // 가장 먼저 실행되어야 Trace ID가 전파됨
        return Ordered.HIGHEST_PRECEDENCE;
    }
}