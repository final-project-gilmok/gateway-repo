package kr.gilmok.gateway.entity;

import jakarta.persistence.*; // Spring Boot 3.x라면 jakarta 필수!
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // 기본 생성자 필수 (JPA용)
@Table(name = "request_logs")
public class RequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // MySQL의 Auto Increment 사용
    private Long id;

    private String traceId;

    @Column(length = 255) // 길이는 넉넉하게
    private String path;

    @Column(length = 10)
    private String method;

    private Integer status;

    private Long latencyMs;

    private LocalDateTime timestamp;

    @Builder
    public RequestLog(String traceId, String path, String method, Integer status, Long latencyMs, LocalDateTime timestamp) {
        this.traceId = traceId;
        this.path = path;
        this.method = method;
        this.status = status;
        this.latencyMs = latencyMs;
        this.timestamp = timestamp;
    }
}
