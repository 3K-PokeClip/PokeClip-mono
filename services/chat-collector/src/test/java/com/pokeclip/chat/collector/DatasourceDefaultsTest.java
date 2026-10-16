package com.pokeclip.chat.collector;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 접속값 셋에 기본값이 없다(auth {@code RequiredPropertiesTest.DB_접속값에_기본값이_남아_있지_않다}를 베꼈다, POK-331).
 *
 * <p>이 서버만 셋에 로컬 기본값이 있었다. 그러면 dev · 운영에서 값이 빠져도 공개 저장소에 적힌 비밀번호로 붙으려 한다.
 * 문자열로 훑지 않고 YAML로 읽어 값을 비교한다: 문자열이면 주석 안 예시까지 걸린다.
 */
class DatasourceDefaultsTest {

    @Test
    void DB_접속값에_기본값이_남아_있지_않다() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        // 파일을 못 읽으면 아래 단언들이 전부 null 비교가 되어 의미를 잃는다.
        assertThat(properties).as("application.yml을 읽지 못했다").isNotNull().isNotEmpty();

        assertThat(properties.getProperty("spring.datasource.username"))
                .as("POSTGRES_USER에 기본값이 붙었다").isEqualTo("${POSTGRES_USER}");
        assertThat(properties.getProperty("spring.datasource.password"))
                .as("POSTGRES_PASSWORD에 기본값이 붙었다: 공개된 비밀번호로 DB에 붙는 창이 열린다")
                .isEqualTo("${POSTGRES_PASSWORD}");
        assertThat(properties.getProperty("spring.datasource.url"))
                .as("POSTGRES_DB에 기본값이 붙었다")
                .endsWith("/${POSTGRES_DB}")
                .as("DB_HOST · DB_PORT의 기본값은 일부러 남긴 것이다: .env에 없어 지우면 로컬이 깨진다")
                .contains("${DB_HOST:localhost}")
                .contains("${DB_PORT:5432}");
    }
}
