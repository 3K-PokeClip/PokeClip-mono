#pragma once

#include <string>

namespace pokeclip {

#ifndef _WIN32
// 비밀이 든 파일을 처음부터 소유자만 읽게(0600) 쓴다 — <path>.tmp에 쓰고 fsync한 뒤 rename한다.
// 있던 파일은 <path>.bak으로 남긴다(libobs obs_data_save_json_safe와 같은 이름이라 obs_data_create_from_json_file_safe가
// 그대로 읽는다). 실패하면 false이고 원래 파일은 그대로다. Windows는 권한 모델이 달라 libobs 저장을 쓴다.
bool WritePrivateFileAtomic(const std::string &path, const std::string &contents);
#endif

} // namespace pokeclip
