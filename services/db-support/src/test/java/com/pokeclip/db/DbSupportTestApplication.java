package com.pokeclip.db;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * 이 모듈의 배선을 실제 부팅으로 재는 시험 전용 앱이다. 서버 넷이 쓰는 꼴(@Import)과 같다.
 * 서버마다 하나씩 있는 배선 시험은 그 서버가 @Import를 빠뜨리지 않았는지만 잰다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(DataSourceLoginConfiguration.class)
class DbSupportTestApplication {
}
