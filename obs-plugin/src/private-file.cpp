#include "private-file.hpp"

#ifndef _WIN32

#include <cerrno>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace pokeclip {

namespace {

bool WriteAll(int fd, const char *data, size_t size)
{
	while (size > 0) {
		ssize_t n = write(fd, data, size);
		if (n < 0) {
			if (errno == EINTR)
				continue;
			return false;
		}
		data += n;
		size -= static_cast<size_t>(n);
	}
	return true;
}

// keepPrevious면 있던 <path>를 <path>.bak으로 옮긴 뒤 바꾸고, 아니면 <path>를 바로 바꾼다(rename이 덮는다).
bool WriteOwnerOnly(const std::string &path, const std::string &contents, bool keepPrevious)
{
	const std::string temp = path + ".tmp";
	const std::string backup = path + ".bak";
	constexpr mode_t kOwnerOnly = S_IRUSR | S_IWUSR;

	// O_NOFOLLOW — tmp 자리에 심어 둔 심볼릭 링크를 따라가 다른 파일을 덮지 않는다.
	int fd = open(temp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW | O_CLOEXEC, kOwnerOnly);
	if (fd < 0)
		return false;
	// 지난 실행이 남긴 tmp가 다른 권한이었어도 내용을 쓰기 전에 0600으로 맞춘다.
	bool ok = fchmod(fd, kOwnerOnly) == 0 && WriteAll(fd, contents.data(), contents.size()) && fsync(fd) == 0;
	ok = close(fd) == 0 && ok;
	if (!ok) {
		unlink(temp.c_str());
		return false;
	}

	if (keepPrevious && access(path.c_str(), F_OK) == 0) {
		if (rename(path.c_str(), backup.c_str()) != 0) {
			unlink(temp.c_str());
			return false;
		}
		chmod(backup.c_str(), kOwnerOnly); // 예전 판이 0644로 남긴 파일이어도 백업은 비밀을 담는다
	}
	if (rename(temp.c_str(), path.c_str()) != 0) {
		unlink(temp.c_str());
		return false;
	}
	return true;
}

} // namespace

bool WritePrivateFileAtomic(const std::string &path, const std::string &contents)
{
	return WriteOwnerOnly(path, contents, true);
}

bool WritePrivateFileReplacingBackup(const std::string &path, const std::string &contents)
{
	// .bak을 먼저 바꾼다 — 본 파일 rename이 커밋 지점이라, 그 앞에서 멈추면 본 파일은 옛 판 그대로다.
	return WriteOwnerOnly(path + ".bak", contents, false) && WriteOwnerOnly(path, contents, false);
}

} // namespace pokeclip

#endif
