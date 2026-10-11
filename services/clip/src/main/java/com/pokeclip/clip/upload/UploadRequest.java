package com.pokeclip.clip.upload;

import java.util.List;

/**
 * 업로드 주문 본문. 형식 검사는 {@link UploadRequestService}가 한다(칸 이름만 싣는 400).
 *
 * @param description   없으면 빈 설명
 * @param outputId      없으면 그 영상의 유일한 영상 파일. 여럿이면 필수다
 * @param tags          없으면 빈 목록(POK-291). 규칙은 「영상 만들기」 문과 같다({@link UploadInfoParser#tags})
 * @param privacyStatus 없으면 비공개(POK-291)
 * @param madeForKids   없으면 아니다(POK-291)
 */
public record UploadRequest(String title, String description, String outputId, List<String> tags, String privacyStatus,
                            Boolean madeForKids) {
}
