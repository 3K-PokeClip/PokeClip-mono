# Media 상자 설치 묶음 — 스크립트(bin/ · bin/lib/)와 상자에서 렌더하는 MediaMTX
# 템플릿. 실행하지 않는다: infra/terraform/modules/media_host 의 부트스트랩이
# `docker create` · `docker cp` 로 /bundle 을 꺼내 소유 · 모드를 고정해 설치한다.
# 저장소 파일을 그대로 COPY 하므로 묶음 내용은 그 커밋의 저장소와 같다.
#
# 빌드 맥락은 infra/media-host 이고 상자는 arm64(Graviton)다.
#   docker buildx build --platform linux/arm64 \
#     -f infra/media-host/bundle/Dockerfile infra/media-host
# 올리는 절차 · 다이제스트를 tfvars(bundle_image)에 넣는 것은 O7c 런북이다.
FROM scratch
COPY bin/ /bundle/bin/
COPY mediamtx.yml.tmpl /bundle/mediamtx.yml.tmpl
