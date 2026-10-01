# Third-party notices

PokeClip for OBS is distributed under the GNU General Public License v2.0 (see `LICENSE`).
It includes or is derived from the following works.

| Component | Where | License |
|---|---|---|
| [obs-multi-rtmp](https://github.com/sorayuki/obs-multi-rtmp) 0.7.4.3 — output lifecycle, dock registration, build scripts | `src/stream-target.cpp`, `src/plugin-main.cpp` (adapted), `cmake/`, `.github/` | GPL-2.0, © SoraYuki |
| [obs-plugintemplate](https://github.com/obsproject/obs-plugintemplate) — CMake modules, CI scripts, `src/plugin-support.*` | `cmake/`, `.github/`, `build-aux/`, `src/plugin-support.*` | GPL-2.0, © OBS Project |
| [obs-browser](https://github.com/obsproject/obs-browser) `panel/browser-panel.hpp` @ 3f0a2cd | `third_party/browser-panel.hpp` | GPL-2.0, © OBS Project |
| [cpp-httplib](https://github.com/yhirose/cpp-httplib) 0.56.0 | `third_party/httplib.h` | MIT, © Yuji Hirose |
| [Preact](https://preactjs.com) 10.29 | dock page bundle (`data/ui`) | MIT |
| [Lucide](https://lucide.dev) (`lucide-preact`) 1.45 | dock page bundle (`data/ui`) | ISC |
| [Pretendard](https://github.com/orioncactus/pretendard) Variable | dock page bundle (`data/ui`), from `web/src/ui/assets/fonts` | SIL Open Font License 1.1 |

The plugin links at runtime against libobs, obs-frontend-api, Qt 6 and libcurl as shipped with OBS Studio.
