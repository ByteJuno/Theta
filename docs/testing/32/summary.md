# Issue 32 verification

Target commit for emulator stress run: `6905f76` (`origin/main`).
Host Release APK SHA-256: `c2a57f5c4c58908c955667492ba1aca21af211a7bcd3f08c24295805f51d8602`.
Environment: `theta_issue17_api36` (API 36 x86_64 emulator).

## `cleanupBlock` stress results

| Viewport (`wm size`) | Runs | Original `#32` flake (`system file picker opened`) | Notes |
|---|---|---|---|
| `1080x2400` (default) | 5 | 0/5 repro (5/5 full case PASS) | `responsive_while_blocked` opened DocumentsUI in `7.51s`–`8.88s` |
| `1080x1920` (short) | 3 | 0/3 repro (3/3 full case PASS) | `responsive_while_blocked` opened DocumentsUI in `8.43s`–`9.03s` |
| `1080x1400` (extreme forced upward scroll) | 2 | 0/2 repro | `responsive_while_blocked` opened DocumentsUI cleanly in `11.97s` and `12.69s`; both runs hit a secondary test-harness logcat ring-buffer eviction during the 60s `BLOCK_MS` wait after ~20 `uiautomator dump` + `swipe` calls |

Total reproduction of the original Issue #32 `system file picker opened` timeout: **0/10**.

## Root-cause context and harness hardening

- Four scroll/tap-clipping fixes (`9587a99`, `be62600`, `fb727fb`, `9d0b2f0`) landed in `scripts/lifecycle-acceptance.py` after the original `#32` report (`8b84d5dc`), which addressed the upward-scroll / clipped-tap failure mode that prevented `Import code APK` from opening the picker.
- While stressing `1080x1400` on Windows, two secondary harness issues were observed and fixed in `scripts/lifecycle-acceptance.py`:
  1. `fresh()` now expands the emulator logcat buffer (`adb logcat -G 4M`) after `clear_logs()` so long 60s blocking-callback waits with repeated UI sweeps do not evict early `ThetaFixture:I` lifecycle markers.
  2. `main()` now guards `signal.signal(signal.SIGALRM, ...)` / `signal.alarm(...)` with `if hasattr(signal, "SIGALRM"):` to match the existing cleanup guards on lines 907 and 920 when running on Windows.

## Limitations

- Stress testing used API 36 emulator (`theta_issue17_api36`) and exercised `cleanupBlock` specifically rather than the entire S1 lifecycle suite.
- Raw per-run logs and screenshots are retained outside Git.
