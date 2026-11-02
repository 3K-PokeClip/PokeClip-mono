package com.pokeclip.clip.purge;

import java.util.List;

/**
 * 탈퇴한 스트리머의 파일을 창고에서 지운다(POK-256). 창고 둘이다: 완성 영상·썸네일 창고(CLIPS_BUCKET)와
 * 녹화 조각 창고(SEGMENT_BUCKET, 1번 media가 올린다).
 *
 * <p>둘 다 없는 파일을 지워도 성공이다. 같은 지우기가 몇 번 다시 돌아도 결과가 같아야 정리기가 이어서 할 수 있다.
 * 실패는 예외로 던진다. 정리기는 그 자리에서 멈추고 다음 순회에 다시 한다.
 */
public interface PurgeStorage {

    /** 완성 영상 창고에서 이 접두사로 시작하는 파일을 전부 지운다. */
    void deleteOutputPrefix(String prefix);

    /** 녹화 조각 창고에서 이 키들을 지운다. {@link #deletesSegments()}가 거짓이면 부르지 않는다. */
    void deleteSegmentObjects(List<String> keys);

    /**
     * 녹화 조각 창고 이름을 아나. 렌더를 끄고 업로드만 켠 배포는 모른다(POK-291): 그때 정리기는 조각 파일도 줄도 안 지운다.
     * 줄이 먼저 사라지면 파일 키를 다시 알 길이 없다.
     */
    boolean deletesSegments();
}
