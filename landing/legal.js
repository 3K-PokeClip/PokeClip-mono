// 이용약관·개인정보 처리방침의 시행일 드롭다운 (POK-269).
// 드롭다운은 <details>라 이 스크립트 없이도 열리고 닫힌다. 여기서는 바깥을 누르거나 Esc를 누르면 닫히게만 한다.
(function () {
  var MENU = 'details.legal-version__menu[open]';

  document.addEventListener('click', function (event) {
    document.querySelectorAll(MENU).forEach(function (menu) {
      if (!menu.contains(event.target)) menu.open = false;
    });
  });

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') return;
    document.querySelectorAll(MENU).forEach(function (menu) {
      menu.open = false;
      var summary = menu.querySelector('summary');
      if (summary) summary.focus();
    });
  });
})();
