package com.pokeclip.auth.streamkey.secret;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 스트림키 암호 저장소 설정(POK-272, 1번 설계 8-A 3)).
 *
 * <p><b>읽기·쓰기 저장소({@code type})와 폐기 정리 대상({@code retireFrom})을 가른다.</b> 이행·병행·롤백 중에는
 * 어느 저장소에서 읽든 재발급·탈퇴가 <b>양쪽 사본을 함께</b> 지워야 해서다. 병행 기간에 dev는
 * {@code retireFrom=postgres,aws}다.
 *
 * <p>검사는 컴팩트 생성자가 한다. 어기면 부팅이 선다:
 * <ul>
 *   <li>{@code refPrefix}는 {@code /}로 끝나고 Secrets Manager 이름 문자({@code [A-Za-z0-9/_+=.@-]})만 쓴다.
 *       {@code :}가 없다. 옛 이름 {@code streamkey:<uuid>}는 Secrets Manager에 그대로 못 옮긴다</li>
 *   <li>{@code refPrefix}는 219자 이하다. 이름 = 접두 + UUID(36자)가 PG 두 칸({@code secrets.ref}·
 *       {@code stream_keys.passphrase_ref}, 둘 다 255자)에 들어가야 한다</li>
 *   <li>{@code retireFrom}이 비지 않고 {@code type}을 포함한다. 쓰는 저장소에서 못 지우는 설정은 재발급한
 *       옛 암호를 영영 남긴다</li>
 * </ul>
 *
 * @param endpoint 비면 진짜 AWS. LocalStack 주소를 넣을 때만 쓴다. 배포 변수는 없다
 * @param region   Secrets Manager 지역. 기존 {@code AWS_REGION}을 읽는다
 */
@ConfigurationProperties(prefix = "pokeclip.stream-key-secret-store")
public record StreamKeySecretStoreProperties(
        Type type,
        String refPrefix,
        Set<Type> retireFrom,
        String endpoint,
        String region) {

    public enum Type { POSTGRES, AWS }

    /** 255 − UUID 36자. */
    static final int MAX_PREFIX_LENGTH = 219;

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9/_+=.@-]+/");

    public StreamKeySecretStoreProperties {
        if (type == null) {
            throw new IllegalStateException("pokeclip.stream-key-secret-store.type이 비었다. postgres 또는 aws");
        }
        if (refPrefix == null || !PREFIX.matcher(refPrefix).matches()) {
            throw new IllegalStateException("pokeclip.stream-key-secret-store.ref-prefix는 '/'로 끝나고 "
                    + "영숫자와 /_+=.@- 만 쓴다(Secrets Manager 이름 규칙)");
        }
        if (refPrefix.length() > MAX_PREFIX_LENGTH) {
            throw new IllegalStateException("pokeclip.stream-key-secret-store.ref-prefix가 " + MAX_PREFIX_LENGTH
                    + "자를 넘는다. 접두 + UUID가 PG 칸 255자에 안 들어간다");
        }
        if (retireFrom == null || retireFrom.isEmpty() || !retireFrom.contains(type)) {
            throw new IllegalStateException("pokeclip.stream-key-secret-store.retire-from은 비지 않고 type("
                    + type + ")을 포함해야 한다. 쓰는 저장소에서 못 지우면 재발급한 옛 암호가 남는다");
        }
        retireFrom = Set.copyOf(retireFrom);
    }

    boolean usesAws() {
        return type == Type.AWS || retireFrom.contains(Type.AWS);
    }

    boolean hasEndpoint() {
        return endpoint != null && !endpoint.isBlank();
    }
}
