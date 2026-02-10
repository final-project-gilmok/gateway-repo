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
@RequiredArgsConstructor
public class LoggingFilter implements GlobalFilter, Ordered {

    private final RequestLogRepository requestLogRepository;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Trace ID 생성 (기존 헤더 있으면 재사용, 없으면 신규 생성)
        String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
        if (traceId == null || traceId.isEmpty()) {
            traceId = UUID.randomUUID().toString().substring(0, 8);
        }

        // 2. 요청 정보 추출
        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();

        // Lambda용 final 변수
        String finalTraceId = traceId;

        // 추가 필드 데이터 준비 (JWT 필터 연동 전이라 임시값 사용)
        String tokenStatus = "UNKNOWN";
        Integer policyVersion = 1;

        // 3. 뒷단 서버로 Trace ID 전달 (헤더 추가)
        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> httpHeaders.set("X-Trace-Id", finalTraceId))
                .build();

        // 4. [개선됨] 요청 로그 출력 (Key-Value 포맷)
        // Loki 등에서 파싱하기 쉽도록 type=REQUEST 형태로 통일
        log.info("type=REQUEST traceId={} method={} path={}", finalTraceId, method, path);

        long startTime = System.currentTimeMillis();

        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .doFinally(signalType -> {
                    // 5. 응답 완료 시점
                    long duration = System.currentTimeMillis() - startTime;
                    int status = (exchange.getResponse().getStatusCode() != null)
                            ? exchange.getResponse().getStatusCode().value() : 500;

                    // 6. 엔티티 생성 (DB 스키마 반영됨)
                    RequestLog logEntity = RequestLog.builder()
                            .requestId(finalTraceId)  // requestId 매핑
                            .path(path)
                            .method(method)
                            .status(status)
                            .latencyMs(duration)
                            .timestamp(LocalDateTime.now())
                            .tokenStatus(tokenStatus)
                            .policyVersion(policyVersion)
                            .build();

                    // 7. DB 저장 및 응답 로그 출력
                    try {
                        requestLogRepository.save(logEntity);

                        // [개선됨] 응답 로그 출력 (Key-Value 포맷)
                        log.info("type=RESPONSE traceId={} status={} latency={}ms", finalTraceId, status, duration);
                    } catch (Exception e) {
                        log.error("type=ERROR traceId={} msg={}", finalTraceId, e.getMessage());
                    }
                });
    }

    @Override
    public int getOrder() {
        // 가장 먼저 실행되어야 Trace ID가 전파됨
        return Ordered.HIGHEST_PRECEDENCE;
    }
}