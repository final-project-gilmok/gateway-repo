package kr.gilmok.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;


@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        // boundedElastic 스레드 풀 제한 - 앱 시작 전에 설정
        System.setProperty("reactor.schedulers.defaultBoundedElasticSize", "30");
        System.setProperty("reactor.schedulers.defaultBoundedElasticQueueSize", "100");

        SpringApplication.run(GatewayApplication.class, args);
    }

}
