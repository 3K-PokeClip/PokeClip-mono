#pragma once

#include <functional>

class QObject;

namespace pokeclip {

// Qt 위젯은 UI 스레드에서만 만진다. libobs 시그널·HTTP 워커에서 넘어올 때 이것을 쓴다.
void SetUiThreadContext(QObject *context);
void RunInUiThread(std::function<void()> task);

} // namespace pokeclip
