package com.pokeclip.upload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 업로드 일꾼(POK-220). 줄(SQS {@code jobs-upload})에서 주문서를 꺼내 완성 영상을 스트리머 유튜브 채널에 비공개로 올리고
 * clip에 알린다.
 *
 * <p>웹 서버가 없고 DB에도 안 붙는다(workers/README.md). 토큰은 주문서에 없고 올리기 직전에 auth에 묻는다.
 * 상태는 clip의 일꾼 문 셋({@code /internal/uploads/{id}/start|session|result})으로만 바꾼다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class UploadWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UploadWorkerApplication.class, args);
    }
}
