package com.pokeclip.chat.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// @ConfigurationPropertiesScan이 없으면 ChzzkProperties가 빈으로 안 올라
// CollectorRunner 주입이 실패한다(auth도 같은 것이 붙어 있다).
@ConfigurationPropertiesScan
@SpringBootApplication
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
