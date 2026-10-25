# prod 산출물 올리기 런북

prod 이미지를 ECR 에 올리고 apex 랜딩을 S3 에 올리는 절차입니다. 계획 M7 의
운영 단계 O7c(이미지)와 O7f 의 5(랜딩 업로드)에 해당합니다. CI 자동 푸시는
아직 없어서(설계 8절 후속 승인 지점 · 40차) 이 런북이 기본 경로입니다.

*   실행자는 kty 입니다. kty 의 IAM 사용자 프로필로 **kty 로컬 터미널**에서
    돌립니다. 에이전트 세션이나 세션의 `!` 로 돌리지 않습니다 — 레지스트리
    호스트 · 버킷 이름에 계정 ID 가 들어 있어 세션 기록에 남습니다.
    `docker tag` · `docker push` 출력에도 같은 호스트가 찍히므로 터미널
    출력을 세션 · 문서 · 채팅에 붙여 넣지 않습니다.
*   쓰기(푸시 · 업로드 · 무효화)마다 실행 직전에 kty 가 승인합니다.

용어:

*   **레지스트리 호스트**: ECR 주소의 앞부분(`<계정>.dkr.ecr.<리전>.amazonaws.com`)
    입니다. 손으로 적지 않고 `prod/registry` 출력 `repo_urls` 에서 읽습니다.
*   **다이제스트**: 이미지 내용의 해시(`sha256:…`)입니다. 태그와 달리 내용이
    바뀌면 값도 바뀌므로, 태스크 정의 · Media 상자는 다이제스트로 이미지를
    가리킵니다.

## 언제

| 단계 | 하는 일 | 선행 |
|---|---|---|
| O7c | 이미지 6종(응답기 · 워커 · 채팅 이미지를 하면 그만큼 더 — 최대 11종) 푸시 · 다이제스트 기록 | M2 머지 · `prod/registry` 적용(O7b) · 이미지별 선행(아래 표) |
| O7f 의 5 | apex 랜딩 업로드 · 무효화 | `prod/edge` 적용 · 2번의 업로드 제외 경로 확인(I2-8) |

O7c 는 O7d(next · auth · clip 태스크 정의가 다이제스트를 씀)와 O7e(Media
셋)보다 먼저 끝냅니다.

## 시작 전

1.  [단계 직전 점검](stage-precheck.md)을 합니다 — O7c 는 `O7c` 묶음,
    랜딩 업로드는 그 하위 단계 묶음입니다. 통과하지 않으면 시작하지
    않습니다.
1.  ECR 푸시 · S3 · CloudFront 무효화는 SCP 판정 대조표(설계 13-1)에서
    허용입니다. 그래도 실행 중 `AccessDenied` 가 나면 그 자리에서 멈추고 계획
    3절 「SCP 멈춤 규칙」 을 따릅니다 — 다시 시도하지 않고, 거부된 동작 ·
    저장소 · 시각을 kty 가 확인합니다.

## ECR 로그인

레지스트리 호스트를 `repo_urls` 에서 읽고, 로그인 토큰은 파이프로만
넘깁니다(명령 인자에 비밀이 남지 않음).

```shell
registry_host="$(terraform -chdir=infra/terraform/prod/registry output -json repo_urls \
  | jq -r '.next' | cut -d/ -f1)"
aws ecr get-login-password --region ap-northeast-2 \
  | docker login --username AWS --password-stdin "${registry_host}"
```

`prod/registry` 를 `init -backend-config=backend.hcl` 로 연 체크아웃에서
돌립니다(출력은 prod 상태에서 읽습니다).

## 이미지

| 저장소 키 | 원천 | 선행 |
|---|---|---|
| `next` | 2번 Dockerfile `web/Dockerfile`(컨텍스트 `web/`) · 환경별 빌드 인자 | 빌드 인자 목록은 web README 「컨테이너 이미지」 절(I2-1) · prod 값은 같은 절의 「prod 영상 주소 값」 표(`NEXT_PUBLIC_MEDIA_LIVE_BASE_URL` · `_PLAYBACK_` · `_CONTROL_`). 필수 빌드 인자가 비면 빌드가 실패합니다 |
| `auth` · `clip` | 3번 Dockerfile · 빌드 방법 | 3번의 준비(I3-8) |
| `media-nginx` | nginx 공식 이미지 — **빌드하지 않습니다** | M2 머지본의 핀 다이제스트 |
| `media-mediamtx` | `media/Dockerfile.mtxhook`(1번 빌드 · 컨텍스트 `media/`) | 아래 「MediaMTX 확인」 |
| `media-sidecar` | `media/Dockerfile`(1번 빌드 · 컨텍스트 `media/`) | I1-1(IAM 인증 · `POSTGRES_AUTH` 전환 · CA 번들) 머지본 · 1단-가(3K-PokeClip/PokeClip-mono#365) 머지본 이후 — 사이드카 SQL 의 내장 함수 `pg_catalog` 한정(앱 사용자 `public` CREATE 가로채기 차단). 그 앞 커밋으로 빌드한 사이드카는 가로채기에 열린다 |
| `media-ingest-auth` | `media/Dockerfile.ingest-auth`(1번 빌드 · 컨텍스트 `media/`) | 응답기 코드가 준비됐을 때만 |
| `render` | `workers/render/Dockerfile`(3번 빌드 · 컨텍스트 `workers/render/`) | 3번의 준비 · 이 이미지를 돌릴 ECS 태스크 정의가 생겼을 때만 |
| `upload` | `workers/upload/Dockerfile`(3번 빌드 · 컨텍스트 `workers/upload/`) | 3번의 준비 · 이 이미지를 돌릴 ECS 태스크 정의가 생겼을 때만 |
| `chat-collector` | `services/chat-collector/Dockerfile`(3번 빌드 · 컨텍스트 `services/`) | 3번의 준비 · 이 이미지를 돌릴 ECS 태스크 정의가 생겼을 때만 |
| `chat-detector` | `services/chat-detector/Dockerfile`(3번 빌드 · 컨텍스트 `services/`) | 3번의 준비 · 이 이미지를 돌릴 ECS 태스크 정의가 생겼을 때만 |

*   next · auth · clip 의 준비가 안 됐으면 O7d 를 시작하지 않습니다.
*   응답기가 준비되지 않았으면 건너뛰고, tfvars 의 응답기 다이제스트를 비워
    둡니다(compose 렌더에서 빠짐). M15 · O8a 에서 다시 합니다.
*   워커 · 채팅 이미지 4종(`render` · `upload` · `chat-collector` ·
    `chat-detector`)은 ECS 로 돌릴 이미지입니다. 돌릴 태스크 정의가 아직
    없으면 건너뜁니다 — O7c 의 조건이 아닙니다.
*   제어면 서버(I1-6)는 O7c 의 조건이 아닙니다. 없으면 nginx 가 404 를
    냅니다.
*   **태그 = 빌드한 커밋 SHA 의 짧은 꼴**입니다. 저장소가 태그 불변이라 같은
    태그를 다시 푸시하면 실패하는 것이 정상입니다 — 다른 내용을 같은 태그로
    덮지 않습니다.
*   **아키텍처**: prod 는 `linux/arm64` 하나입니다(결정 146 — ECS 태스크 ·
    Media 상자 모두 Graviton). `--platform linux/arm64` 로 빌드하고 arm64 한
    변형만 올립니다 — 멀티 아키텍처 인덱스는 쓰지 않습니다. kty 로컬(Apple
    Silicon)에서 네이티브로 빌드되고, 실행 이미지는 푸시 전에 로컬에서 돌려
    기동을 확인합니다.

빌드한 이미지를 올리는 꼴입니다(`<키>` · `<태그>` 는 표의 값).

```shell
docker tag <로컬 이미지> "${registry_host}/pokeclip-prod/<키>:<태그>"
docker push "${registry_host}/pokeclip-prod/<키>:<태그>"
```

nginx 는 빌드 대신 핀 다이제스트로 받아 다시 태그합니다. 받을 때 Media 상자의
플랫폼을 꼭 지정합니다 — 핀은 여러 아키텍처를 묶은 인덱스 다이제스트라 지정한
변형 하나만 로컬에 받아지고(지정하지 않으면 호스트 것), 다시 태그해 푸시하면
그 변형 하나만 올라갑니다. arm64 한 변형만 올리는 것이 정책입니다(결정 146).

*   `<Media 상자 플랫폼>` 은 `linux/arm64` 입니다(결정 146 — `prod/media` 의
    Graviton 유형).
*   같은 핀의 다른 변형이 이미 로컬에 있으면 pull 이 「cannot overwrite
    digest」 로 실패하고 옛 변형이 남습니다. 아래 확인이 그것을 잡습니다 —
    다르면 푸시하지 않고, 그 로컬 이미지를 지운 뒤 다시 받습니다.

```shell
docker pull --platform <Media 상자 플랫폼> nginx@<M2 머지본의 핀 다이제스트>
docker image inspect --format '{{.Os}}/{{.Architecture}}' \
  nginx@<M2 머지본의 핀 다이제스트>   # <Media 상자 플랫폼> 과 같아야 함
docker tag nginx@<M2 머지본의 핀 다이제스트> \
  "${registry_host}/pokeclip-prod/media-nginx:<태그>"
docker push --platform <Media 상자 플랫폼> \
  "${registry_host}/pokeclip-prod/media-nginx:<태그>"
```

Docker 의 classic 이미지 저장소에서는 푸시한 것이 그 변형 하나의
매니페스트라 다이제스트가 핀과 다릅니다. containerd 이미지 저장소를 켠 Docker 는
인덱스를 통째로 올릴 수 있어, 위처럼 `docker push --platform` 으로 대상 플랫폼
하나만 올립니다([docker image push](https://docs.docker.com/reference/cli/docker/image/push/)
의 `--platform`). 기록에는 푸시 출력의 다이제스트를 씁니다(아래 「기록」).

### MediaMTX 확인

*   빌드 전에 `media/Dockerfile.mtxhook` 의 `FROM` 이 현행 포크 태그
    `v1.21.1-pokeclip.2` 인지 대조합니다. 현행 태그의 정본은 볼트 런북
    「mediamtx-커스텀이미지-반영」 입니다.
*   **포크 원본 이미지를 그대로 미러링하지 않습니다.** 훅 바이너리가 없어
    녹화 이벤트가 기록되지 않습니다.
*   빌드한 이미지에 훅 바이너리가 있고 실행 계정이 10002 인지 봅니다. 이
    이미지에는 셸이 없어 컨테이너를 만들어 파일 목록을 꺼냅니다.

```shell
docker image inspect --format '{{.Config.User}}' <로컬 이미지>   # 10002:10002
cid="$(docker create <로컬 이미지>)"
docker export "${cid}" | tar -t | grep -x 'hooks-bin/mtxhookwrite'
docker rm "${cid}"
```

### 설치 묶음 · 루프백 송출기

M9 에서 `prod/registry` 에 더한 저장소 둘입니다. 둘 다 `prod/media` apply
(O7e)의 입력이라 O7e 전에 올립니다. 먼저 `prod/registry` 재적용(저장소 둘
생성)이 끝나야 합니다([prod/registry](../prod/registry/README.md) 「적용」).
위 「언제」 · 아래 「기록」 의 6종에 이 둘을 더해 셉니다.

| 저장소 키 | 원천 | 선행 |
|---|---|---|
| `media-host-bundle` | `infra/media-host/bundle/Dockerfile`(빌드 · 컨텍스트 `infra/media-host`) | 그 Dockerfile 이 머지된 커밋 |
| `media-loopback-sender` | `linuxserver/ffmpeg` 공식 이미지 — **빌드하지 않습니다** | `infra/media-host/images.json` 의 `loopback_sender` 핀 |

**설치 묶음**은 실행하지 않는 이미지(`FROM scratch`)입니다. 상자의 첫 부팅
설치가 `docker create` · `docker cp` 로 스크립트 · MediaMTX 템플릿을 꺼내
설치합니다. 묶음 내용이 그 커밋의 저장소와 같아야 하므로 `infra/media-host`
에 고친 파일이 없는 체크아웃에서 빌드합니다. 증명(attestation) 매니페스트가
붙으면 인덱스가 되므로 끕니다.

```shell
git status --porcelain -- infra/media-host   # 빈 출력이어야 함
docker buildx build --platform linux/arm64 --provenance=false --sbom=false \
  --load -t pokeclip-media-host-bundle:<태그> \
  -f infra/media-host/bundle/Dockerfile infra/media-host
docker image inspect --format '{{.Os}}/{{.Architecture}}' \
  pokeclip-media-host-bundle:<태그>   # linux/arm64
work="$(mktemp -d)"
cid="$(docker create pokeclip-media-host-bundle:<태그> /bundle-not-run)"
docker export "${cid}" | tar -t | sed -n 's|^bundle/\(.*[^/]\)$|\1|p' \
  | LC_ALL=C sort >"${work}/bundle.txt"
docker rm "${cid}"
awk '/^readonly REQUIRED_FILES=\(/ { on = 1; next }
  on && /^\)/ { exit }
  on { print $1 }' \
  infra/terraform/modules/media_host/templates/bootstrap.sh.tftpl \
  | LC_ALL=C sort >"${work}/required.txt"
LC_ALL=C comm -23 "${work}/required.txt" "${work}/bundle.txt"   # 빈 출력(빠진 것)
LC_ALL=C comm -13 "${work}/required.txt" "${work}/bundle.txt"   # 빈 출력(남는 것)
rm -rf -- "${work}"
docker tag pokeclip-media-host-bundle:<태그> \
  "${registry_host}/pokeclip-prod/media-host-bundle:<태그>"
docker push "${registry_host}/pokeclip-prod/media-host-bundle:<태그>"
```

*   묶음 파일 목록의 정본은 상자 부트스트랩의 `REQUIRED_FILES` 하나입니다
    (`modules/media_host` `templates/bootstrap.sh.tftpl`). 위 `awk` 가 그
    배열을 뽑고, 묶음 이미지의 `/bundle` 아래 파일 목록과 정렬해 비교합니다.
*   `comm -23` 은 목록에 있는데 묶음에 없는 파일(빠진 것)을, `comm -13` 은
    묶음에 있는데 목록에 없는 파일(남는 것)을 줄마다 하나씩 찍습니다. 둘 다
    빈 출력일 때만 푸시합니다.
*   빠진 것이 있으면 그 묶음으로는 상자 설치가 `MEDIA BOOTSTRAP
    missing <경로>` 로 실패합니다. 남는 것은 `bin/` 에 파일을 더하고 목록을 고치지
    않은 경우입니다. 어느 쪽이든 저장소에서 목록이나 파일을 고쳐 머지한 뒤
    다시 빌드합니다. 목록 자체는 `modules/media_host` 시험
    (`bootstrap_requires_every_bundle_file`)이 단언합니다.

**루프백 송출기**는 nginx 와 같은 꼴로 핀을 받아 다시 태그합니다.

```shell
docker pull --platform linux/arm64 linuxserver/ffmpeg@<images.json 의 loopback_sender 핀>
docker image inspect --format '{{.Os}}/{{.Architecture}}' \
  linuxserver/ffmpeg@<images.json 의 loopback_sender 핀>   # linux/arm64
docker tag linuxserver/ffmpeg@<images.json 의 loopback_sender 핀> \
  "${registry_host}/pokeclip-prod/media-loopback-sender:<태그>"
docker push --platform linux/arm64 \
  "${registry_host}/pokeclip-prod/media-loopback-sender:<태그>"
```

푸시한 두 다이제스트는 `prod/media` 의 tfvars `image_digests.bundle` ·
`image_digests.loopback_sender` 에 넣습니다.

## 기록

푸시한 이미지마다 저장소 · 태그 · 다이제스트 · 빌드한 커밋 SHA(next 는 mono
develop 커밋)를 적습니다. 다이제스트는 푸시 출력의 `digest:` 이거나 아래
명령의 값입니다.

```shell
aws ecr describe-images --region ap-northeast-2 \
  --repository-name pokeclip-prod/<키> --image-ids imageTag=<태그> \
  --query 'imageDetails[0].imageDigest' --output text
```

다이제스트는 `prod/platform` · `prod/media` 의 tfvars(추적하지 않음)에
넣습니다. O7c 의 통과 조건은 푸시한 6종(응답기 · 워커 · 채팅 이미지를 했으면
그것까지)의 다이제스트가 모두 기록 · 기입된 것입니다.

푸시가 다 끝나면 로그아웃합니다. `docker login` 은 토큰(12시간)을 Docker
자격 증명 저장소(설정에 따라 `~/.docker/config.json`)에 남깁니다.

```shell
docker logout "${registry_host}"
```

## 랜딩 업로드(O7f 의 5)

원천은 mono `landing/` 입니다(빌드 없음 · ADR-089). 버킷 · 배포는 `prod/edge`
출력 `apex_bucket_name` · `apex_distribution_id` 에서 읽습니다.

1.  2번이 넘긴 업로드 제외 경로(I2-8 — 예: `landing/scripts/` 아래 검사
    스크립트)를 `--exclude` 로 붙여 dry-run 으로 대상을 봅니다.

    ```shell
    aws s3 sync landing/ "s3://<apex_bucket_name>" \
      --exclude '<제외 경로>' --dryrun
    ```

1.  대상 목록을 kty 가 보고 승인한 뒤 같은 명령을 `--dryrun` 없이
    돌립니다. 버킷에만 있는 옛 파일을 지우는 `--delete` 는 dry-run 결과에서
    지울 목록을 본 뒤에만 붙입니다.
1.  무효화합니다.

    ```shell
    aws cloudfront create-invalidation \
      --distribution-id <apex_distribution_id> --paths '/*'
    ```

1.  배포 주소 · 시각을 kty 가 2번에게 알립니다(I2-8).

## CI 로 옮길 때

CI 자동 푸시는 설계 8절 후속 승인 지점(40차)을 따릅니다 — CI 푸시 역할 ·
apex 업로드 역할과 그 신뢰 조건을 만드는 별도 PR 과 kty 결정이 필요합니다.
그 뒤에도 이 런북은 CI 가 안 될 때의 수동 대행 경로로 남깁니다.

## See also

*   계획 M7 「c5 런북 내용」 · O7c · 3절 「SCP 멈춤 규칙」
*   [prod/registry](../prod/registry/README.md) · [단계 직전 점검](stage-precheck.md)
*   설계 3-1 의 ⑦ · 8절 후속 승인 지점 · 13-1 SCP 판정 대조표
