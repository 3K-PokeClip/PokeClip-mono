package com.pokeclip.clip.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 접속값에 기본값이 남으면 공개 저장소의 .env.example에 적힌 값으로 실제 DB에
 * 붙는 창이 열린다(POK-161). auth의 RequiredPropertiesTest와 같은 판정이다.
 *
 * <p>문자열로 훑지 않고 YAML로 파싱한다 — 문자열이면 주석 안 예시까지 매칭돼
 * 거짓 음성·거짓 양성이 양쪽으로 열린다.
 */
class RequiredPropertiesTest {

    @Test
    void DB_접속값에_기본값이_남아_있지_않다() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).as("application.yml을 읽지 못했다").isNotNull().isNotEmpty();

        assertThat(properties.getProperty("spring.datasource.username"))
                .as("POSTGRES_USER에 기본값이 붙었다")
                .isEqualTo("${POSTGRES_USER}");
        assertThat(properties.getProperty("spring.datasource.password"))
                .as("POSTGRES_PASSWORD에 기본값이 붙었다 — 공개된 비밀번호로 DB에 붙는 창이 열린다")
                .isEqualTo("${POSTGRES_PASSWORD}");
        assertThat(properties.getProperty("spring.datasource.url"))
                .as("POSTGRES_DB에 기본값이 붙었다")
                .endsWith("/${POSTGRES_DB}")
                .as("DB_HOST·DB_PORT의 기본값은 일부러 남긴 것이다 — .env에 없어 지우면 로컬이 깨진다")
                .contains("${DB_HOST:localhost}")
                .contains("${DB_PORT:5432}");
    }
}
