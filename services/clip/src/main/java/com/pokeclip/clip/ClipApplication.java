package com.pokeclip.clip;

import com.pokeclip.web.CorsConfig;
import com.pokeclip.web.WebConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * 방송 세션·세그먼트·클립·승인을 담당하는 서버. ADR-022로 auth와 프로세스가 갈렸다.
 *
 * <p>구 {@code core}다. auth가 빠져나가 clip만 남았으므로 이름을 내용에 맞췄다.
 *
 * <p>web-support({@code com.pokeclip.web})가 이 앱의 패키지 밖이라 스캔에 안 걸린다.
 * AuthApplication과 같은 이유로 명시적으로 끌어온다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan({"com.pokeclip.clip", "com.pokeclip.web"})
@Import({CorsConfig.class, WebConfig.class})
public class ClipApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClipApplication.class, args);
    }
}
