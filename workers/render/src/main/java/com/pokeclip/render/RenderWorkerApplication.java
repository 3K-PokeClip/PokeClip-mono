package com.pokeclip.render;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 렌더 일꾼(POK-246). 줄(SQS {@code jobs-render})에서 주문서를 꺼내 S3 조각으로 mp4를 만들고 clip에 보고한다.
 *
 * <p>사람이 부르는 문이 없다. 웹 서버를 띄우지 않는다. DB에도 붙지 않는다(workers/README.md). 필요한 것은 전부
 * 주문서에 실려 오고(계약1 2절 「메시지는 자족적」), 결과는 보고 문 {@code /internal/jobs/{jobId}/events}로만 나간다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RenderWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(RenderWorkerApplication.class, args);
    }
}
