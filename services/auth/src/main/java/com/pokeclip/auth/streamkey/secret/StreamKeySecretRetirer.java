package com.pokeclip.auth.streamkey.secret;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 폐기된 스트림키의 암호 사본을 {@code retire-from}의 <b>모든</b> 저장소에서 지운다(POK-272, 1번 설계 8-A 1) E2).
 * 재발급의 커밋 뒤 정리와 탈퇴 정리가 이것을 부른다.
 *
 * <p>읽기·쓰기 저장소 하나만 지우면 안 되는 이유: 이행·병행·롤백 중에는 같은 키의 사본이 PG와 Secrets Manager
 * 양쪽에 있을 수 있다. aws 상태 탈퇴에서 PG 사본이, postgres 롤백 중 재발급에서 Secrets Manager 사본이 남는다.
 *
 * <p>지울 이름은 셋이다. {@code ref} 그대로, {@code refPrefix + uuid}(이행 뒤 이름), {@code "streamkey:" + uuid}
 * (이행 전 이름). 이행 실행기가 행의 이름을 바꾼 뒤에도 옛 이름의 PG 사본이 남아 있어서다. uuid를 못 뽑으면
 * {@code ref}만 지운다.
 *
 * <p>저장소·이름마다 따로 시도한다. 하나가 실패해도 나머지를 지우고, 마지막에 처음 실패를 다시 던진다
 * ({@code WithdrawalService.deleteQuietly}와 같은 규칙). 없는 이름의 삭제는 성공이다(두 저장소 모두 멱등).
 */
public class StreamKeySecretRetirer {

    static final String LEGACY_PREFIX = "streamkey:";

    private static final Pattern UUID_TAIL = Pattern.compile(
            "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$");

    private final List<SecretStore> stores;
    private final String refPrefix;

    /** 운영에서는 {@link StreamKeySecretStoreConfig}만 만든다. 공개인 것은 다른 패키지 시험이 가짜 저장소로 끼우려고다. */
    public StreamKeySecretRetirer(List<SecretStore> stores, String refPrefix) {
        this.stores = List.copyOf(stores);
        this.refPrefix = refPrefix;
    }

    public void retire(String ref) {
        RuntimeException failure = null;
        for (SecretStore store : stores) {
            for (String name : namesOf(ref)) {
                try {
                    store.delete(name);
                } catch (RuntimeException e) {
                    failure = failure != null ? failure : e;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    Set<String> namesOf(String ref) {
        Set<String> names = new LinkedHashSet<>();
        names.add(ref);
        String uuid = uuidOf(ref);
        if (uuid != null) {
            names.add(refPrefix + uuid);
            names.add(LEGACY_PREFIX + uuid);
        }
        return names;
    }

    /** 이름 끝의 UUID. 옛 이름({@code streamkey:<uuid>})과 새 이름({@code <접두><uuid>}) 둘 다 끝이 UUID다. */
    static String uuidOf(String ref) {
        Matcher m = UUID_TAIL.matcher(ref);
        return m.find() ? m.group(1) : null;
    }
}
