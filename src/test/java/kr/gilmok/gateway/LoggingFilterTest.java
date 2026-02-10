package kr.gilmok.gateway;


import kr.gilmok.gateway.entity.RequestLog;
import kr.gilmok.gateway.filter.LoggingFilter;
import kr.gilmok.gateway.repository.RequestLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoggingFilterTest {

    @Mock
    private RequestLogRepository requestLogRepository; // 가짜 저장소

    @Mock
    private GatewayFilterChain chain; // 가짜 체인

    @InjectMocks
    private LoggingFilter loggingFilter; // 테스트 대상

    @Test
    @DisplayName("요청이 끝나면 로그 저장소의 save()가 호출되어야 한다")
    void shouldSaveLogAfterRequest() {
        // given
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/test").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        // 체인이 정상적으로 흘러간다고 가정
        when(chain.filter(any())).thenReturn(Mono.empty());

        // when
        loggingFilter.filter(exchange, chain).block(); // 실행

        // then
        // save() 메서드가 한 번이라도 호출되었는지 확인
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(requestLogRepository).save(any(RequestLog.class));
        });
    }
}