# auth · clip 작업 역할 정책 — 코드가 실제로 부르는 AWS 동작만(결정 135).
# 대상 자원은 런타임 1단이 만들고 ARN 을 app_task_resource_arns 로 낸다. 키가
# 없으면(기능 꺼짐) 그 문장을 만들지 않는다. 근거(파일:행)는 README 「작업 역할」.
#
# 동작을 바꾸는 코드 변경(새 AWS 호출)은 이 표도 함께 고친다 — 빠지면 그
# 기능이 운영에서 권한 거부로 실패한다.

locals {
  auth_arns = local.task_resource_arns.auth
  clip_arns = local.task_resource_arns.clip

  # 스트림 키 비밀 이름 = 접두 + UUID(auth 가 만든다) — 접두 와일드카드만 쓴다.
  stream_key_secret_arn = "arn:aws:secretsmanager:${local.region}:${local.account_id}:secret:${var.stream_key_secret_prefix}*"

  db_user_arns = {
    for service, user in local.db_users :
    service => "arn:aws:rds-db:${local.region}:${local.account_id}:dbuser:${local.runtime.rds_resource_id}/${user}"
  }

  # 서비스마다 후보 문장. resources 가 빈 문장은 아래에서 뺀다.
  task_role_candidates = {
    auth = [
      {
        sid       = "StreamKeySecrets"
        actions   = ["secretsmanager:CreateSecret", "secretsmanager:DeleteSecret", "secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
        resources = [local.stream_key_secret_arn]
      },
      {
        sid       = "ProfilePhotoObjects"
        actions   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        resources = [for arn in compact([try(local.auth_arns.profile_photo_bucket_arn, "")]) : "${arn}/*"]
      },
    ]
    clip = [
      {
        sid       = "BroadcastIntake"
        actions   = ["sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = compact([try(local.clip_arns.broadcast_queue_arn, "")])
      },
      {
        sid     = "OrderQueues"
        actions = ["sqs:SendMessage"]
        resources = sort(compact([
          try(local.clip_arns.render_queue_arn, ""),
          try(local.clip_arns.upload_queue_arn, ""),
          try(local.clip_arns.thumbnail_queue_arn, ""),
        ]))
      },
      {
        # 일꾼이 포기한 주문을 DLQ 에서 읽어 정리한다(DlqReconciler).
        sid       = "DeadLetterQueues"
        actions   = ["sqs:DeleteMessage", "sqs:ReceiveMessage"]
        resources = sort(compact([try(local.clip_arns.render_dlq_arn, ""), try(local.clip_arns.upload_dlq_arn, "")]))
      },
      {
        # GetObject 는 서명 URL(파일 받기 · 썸네일)이 이 역할 자격으로 서명되기 때문이다.
        sid       = "ClipObjects"
        actions   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        resources = [for arn in compact([try(local.clip_arns.clips_bucket_arn, "")]) : "${arn}/*"]
      },
      {
        sid       = "ClipList"
        actions   = ["s3:ListBucket"]
        resources = compact([try(local.clip_arns.clips_bucket_arn, "")])
      },
      {
        # 탈퇴 정리가 회원의 녹화 조각을 지운다(S3PurgeStorage).
        sid       = "SegmentDelete"
        actions   = ["s3:DeleteObject"]
        resources = [for arn in compact([try(local.clip_arns.segment_bucket_arn, "")]) : "${arn}/*"]
      },
      {
        # 트랙별 미리 듣기의 조각 서명 URL(SegmentAudioSigner)이 이 역할 자격으로 서명된다(POK-389).
        sid       = "SegmentRead"
        actions   = ["s3:GetObject"]
        resources = [for arn in compact([try(local.clip_arns.segment_bucket_arn, "")]) : "${arn}/*"]
      },
    ]
  }

  db_connect_statements = {
    for service, arn in local.db_user_arns : service => {
      sid       = "DbConnect"
      actions   = ["rds-db:connect"]
      resources = var.app_db_auth == "iam" ? [arn] : []
    }
  }

  task_role_statements = {
    next = []
    auth = [
      for statement in concat(local.task_role_candidates.auth, [local.db_connect_statements.auth]) :
      statement if length(statement.resources) > 0
    ]
    clip = [
      for statement in concat(local.task_role_candidates.clip, [local.db_connect_statements.clip]) :
      statement if length(statement.resources) > 0
    ]
  }
}
