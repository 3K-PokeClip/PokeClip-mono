package com.pokeclip.chat.detector.db;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rds.RdsUtilities;

/**
 * RDS IAM 인증 토큰(15분 유효 서명 URL)을 만든다. AWS에 기대는 코드는 이 파일뿐이다
 * (media {@code pgconf/iam.go}와 같은 경계).
 *
 * <p>함수 하나짜리로 둔 것은 시험에서 갈아 끼우는 자리라서다. 실제 RDS 없이는 토큰을 받아 줄
 * 서버가 없으므로, 시험은 컨테이너 비밀번호를 돌려주는 가짜를 꽂는다.
 */
@FunctionalInterface
public interface RdsTokenSigner {

    String sign(String host, int port, String user);

    /**
     * 서명은 로컬 계산이다 — 네트워크를 안 탄다. 자격 증명 조회만 밖으로 나갈 수 있고
     * (ECS 태스크 역할 · IMDS), 그 값은 SDK가 만료 전까지 캐시한다.
     */
    static RdsTokenSigner rds(Region region, AwsCredentialsProvider credentials) {
        RdsUtilities utilities = RdsUtilities.builder()
                .region(region)
                .credentialsProvider(credentials)
                .build();
        return (host, port, user) -> utilities.generateAuthenticationToken(
                request -> request.hostname(host).port(port).username(user));
    }
}
