package com.pokeclip.chat.detector.db;

import com.zaxxer.hikari.HikariCredentialsProvider;
import com.zaxxer.hikari.util.Credentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hikari가 <b>새 연결을 만들 때마다</b> 부르는 공급자다(HikariCP 7.0.2 {@code PoolBase.newConnection}).
 * 그래서 토큰이 15분 만에 만료돼도 이미 붙은 연결은 안 끊기고, 다음 연결은 새 토큰으로 붙는다.
 * 풀을 처음 띄울 때도 한 번 더 불린다({@code PoolBase.initializeDataSource}). 그 값은 드라이버
 * 기본값으로만 박히고 실제 접속에는 안 쓰여, {@code db.iam_token_issued}가 연결 수보다 하나 많다.
 *
 * <p>AWS 공식 JDBC 래퍼를 안 쓰고 이 길을 고른 이유: 래퍼가 {@code socketTimeout: 10}(초)을 밀리초로
 * 읽어 시한이 사실상 없어진다(aws-advanced-jdbc-wrapper#2157). 그 시한이 반개방 스톨을 끊는 유일한
 * 장치다({@code application.yml} 주석).
 */
public class RdsIamCredentialsProvider implements HikariCredentialsProvider {

    private static final Logger log = LoggerFactory.getLogger(RdsIamCredentialsProvider.class);

    private final RdsTokenSigner signer;
    private final String host;
    private final int port;
    private final String user;

    public RdsIamCredentialsProvider(RdsTokenSigner signer, String host, int port, String user) {
        this.signer = signer;
        this.host = host;
        this.port = port;
        this.user = user;
    }

    /**
     * 토큰 값은 어디에도 안 찍는다. 15분 유효지만 서버가 그 뒤에도 얼마간 받아 주는 재사용 가능한
     * 자격이다(media 실험 RDS 실측 약 20분).
     *
     * <p>실패의 원인 문구도 싣지 않고 종류만 싣는다. SDK 자격 증명 오류에는 프로필 이름 · 설정 파일
     * 경로가 섞일 수 있다(media {@code iam.go}와 같은 규칙).
     */
    @Override
    public Credentials getCredentials() {
        String token;
        try {
            token = signer.sign(host, port, user);
        } catch (RuntimeException e) {
            String causeType = e.getClass().getSimpleName();
            log.warn("db.iam_token_failed host={} user={} causeType={}", host, user, causeType);
            throw new IllegalStateException("RDS IAM 토큰 생성 실패(원인 종류 " + causeType
                    + "). AWS_REGION · 실행 역할의 rds-db:connect 권한을 확인하라");
        }
        log.info("db.iam_token_issued host={} user={}", host, user);
        return Credentials.of(user, token);
    }
}
