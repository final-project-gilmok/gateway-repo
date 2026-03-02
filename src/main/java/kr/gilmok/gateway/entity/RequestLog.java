package kr.gilmok.gateway.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "request_logs")
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ts", nullable = false)
    private LocalDateTime timestamp;  // DB의 ts와 매핑

    @Column(name = "request_id", length = 64)
    private String requestId;         // traceId -> requestId로 변경

    // event_id는 외래키(FK)라면 연관관계 매핑 필요, 단순 값이면 Long
    @Column(name = "event_id")
    private Long eventId;

    @Column(length = 255)
    private String path;

    @Column(length = 10)
    private String method;

    @Column(name = "status_code")
    private Integer status;           // DB 컬럼명 지정

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Builder
    public RequestLog(LocalDateTime timestamp, String requestId, String path,
                      String method, Integer status, Long latencyMs) {
        this.timestamp = timestamp;
        this.requestId = requestId;
        this.path = path;
        this.method = method;
        this.status = status;
        this.latencyMs = latencyMs;
    }
}
