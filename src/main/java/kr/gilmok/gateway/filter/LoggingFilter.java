package kr.gilmok.gateway.filter;

import io.micrometer.tracing.Tracer;
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

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class LoggingFilter implements GlobalFilter, Ordered {

    private final RequestLogRepository requestLogRepository;
    private final Tracer tracer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1. Trace ID
        String traceId = (tracer.currentSpan() != null)
                ? tracer.currentSpan().context().traceId()
                : exchange.getRequest().getHeaders().getFirst("X-Trace-Id");

        // ✅ 피드백 2: "unknown" 대신 요청 고유 ID로 fallback
        if (traceId == null || traceId.isEmpty()) {
            traceId = exchange.getRequest().getId();
        }

        String path = exchange.getRequest().getURI().getPath();
        String method = exchange.getRequest().getMethod().name();
        LocalDateTime requestTime = LocalDateTime.now();
        String finalTraceId = traceId;

        ServerHttpRequest modifiedRequest = exchange.getRequest().mutate()
                .headers(httpHeaders -> httpHeaders.set("X-Trace-Id", finalTraceId))
                .build();

        log.info("type=REQUEST traceId={} method={} path={}", finalTraceId, method, path);
        long startTime = System.currentTimeMillis();

        // ✅ 피드백 1: saveLogMono를 분리해 성공/실패 모두 DB 저장
        Mono<Void> saveLogMono = Mono.defer(() -> {
            long duration = System.currentTimeMillis() - startTime;
            int status = (exchange.getResponse().getStatusCode() != null)
                    ? exchange.getResponse().getStatusCode().value() : 500;

            return Mono.fromRunnable(() -> {
                        try {
                            RequestLog logEntity = RequestLog.builder()
                                    .requestId(finalTraceId)
                                    .path(path)
                                    .method(method)
                                    .status(status)
                                    .latencyMs(duration)
                                    .timestamp(requestTime)
                                    .build();
                            requestLogRepository.save(logEntity);
                            log.info("type=RESPONSE traceId={} status={} latency={}ms",
                                    finalTraceId, status, duration);
                        } catch (Exception e) {
                            log.error("type=ERROR traceId={} msg={}", finalTraceId, e.getMessage());
                            throw e;
                        }
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(Duration.ofSeconds(3))  // ← 여기 추가
                    .then()
                    .onErrorResume(e -> {
                        log.error("type=LOG_SAVE_ERROR traceId={} msg={}", finalTraceId, e.getMessage());
                        return Mono.empty();
                    });
        });

        // ✅ 성공 시: .then(saveLogMono)
        // ✅ 실패 시: .onErrorResume → 로그 저장 후 원래 에러 재전파
        return chain.filter(exchange.mutate().request(modifiedRequest).build())
                .then(saveLogMono)
                .onErrorResume(chainError ->
                        saveLogMono.then(Mono.error(chainError)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
