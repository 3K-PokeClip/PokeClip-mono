#pragma once

#include <string>

namespace pokeclip {

#ifndef _WIN32
// 비밀이 든 파일을 처음부터 소유자만 읽게(0600) 쓴다 — <path>.tmp에 쓰고 fsync한 뒤 rename한다.
// 있던 파일은 <path>.bak으로 남긴다(libobs obs_data_save_json_safe와 같은 이름이라 obs_data_create_from_json_file_safe가
// 그대로 읽는다). 실패하면 false이고 원래 파일은 그대로다. Windows는 권한 모델이 달라 libobs 저장을 쓴다.
bool WritePrivateFileAtomic(const std::string &path, const std::string &contents);

// WritePrivateFileAtomic과 같되 직전 판을 남기지 않는다 — <path>.bak에 같은 내용을 먼저 쓰고 <path>를 바꾼다.
// 자격증명을 지우는 저장(연결 해제)용이다. 직전 판을 .bak으로 남기면 본 파일이 깨졌을 때 libobs가 그것을 읽어
// 지운 키가 되살아난다(POK-285). 실패하면 false이고, .bak만 새 판이 되고 본 파일은 옛 판일 수 있다 — 어긋나도
// 키가 사라지는 쪽뿐이다. 프로세스 비정상 종료까지만 지킨다(디렉터리 fsync가 없어 전원 장애 뒤 순서는 보장 안 함).
bool WritePrivateFileReplacingBackup(const std::string &path, const std::string &contents);
#endif

} // namespace pokeclip
