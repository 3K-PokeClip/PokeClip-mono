package com.pokeclip.auth;

import com.pokeclip.web.CorsConfig;
import com.pokeclip.web.WebConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * 로그인·토큰·스트림키를 담당하는 서버. ADR-022로 clip과 프로세스가 갈렸다.
 *
 * <p>web-support(`com.pokeclip.web`)는 이 앱의 패키지 밖이라 컴포넌트 스캔에
 * 안 걸린다. 그래서 설정 두 개를 명시적으로 {@code @Import} 하고,
 * {@code @ConfigurationPropertiesScan}에도 그 패키지를 같이 적는다.
 * 스캔 대상을 직접 적으면 기본값(이 클래스의 패키지)을 덮어쓰므로
 * {@code com.pokeclip.auth}도 빠뜨리지 않고 함께 적어야 한다.
 */
@SpringBootApplication
@ConfigurationPropertiesScan({"com.pokeclip.auth", "com.pokeclip.web"})
@Import({CorsConfig.class, WebConfig.class})
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
