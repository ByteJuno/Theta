# Issue 51 verification

Target commit: `dc43c32` (`origin/main`, after merging PRs #124–#128).

Unchanged host Release build (`:app:assembleRelease --configuration-cache --configuration-cache-problems=fail` with `THETA_RELEASE_*` unset; debug-signed Release for instrumentation signature parity):

- Host Release APK SHA-256: `1e31a2d6f07e141598d28ad267e78ac6848f2e74d7b634b50e4f5ea71942e6ce`
- R8 `mapping.txt` SHA-256: `95e8ab60a91f8f52565cae809515786e53501f3df2870ba540df0d07964fac56`

## Emulator acceptance results

Both dedicated emulators (`emulator-5588`) ran `python -I scripts/ledger-runtime-acceptance.py` against the exact same unchanged Release APK and R8 mapping above:

| Environment | AVD | SDK | Phases | Result |
|---|---|---|---|---|
| API 36 | `theta_issue17_api36` | `36` | `launcher-eager`, `execute`, `recover`, `ready-create`, `ready-restart`, `storage`, `startup-failure`, `recover` | PASS (8/8, `passed: true`, launcher PID 4299, `eager_initialization: true`) |
| API 33 | `theta_issue51_api33` | `33` | `launcher-eager`, `execute`, `recover`, `ready-create`, `ready-restart`, `storage`, `startup-failure`, `recover` | PASS (8/8, `passed: true`, launcher PID 2393, `eager_initialization: true`) |

## Acceptance runner updates included

Two stale checks in the acceptance harness were updated (host APK and R8 mapping were not changed):

1. `scripts/ledger-runtime/LedgerInstrumentation.java`: added `"host/generate_image"` (merged in PR #107) to the expected built-in host tool directory set alongside `"host/clock"`, `"host/blob_read"`, and `"host/screen_info"`.
2. `scripts/ledger-runtime-acceptance.py`: expanded `ALLOWED_AVDS` to include `theta_issue51_api33` alongside `theta_issue17_api36` while keeping the `serial == 'emulator-5588'` guard unchanged.

## Limitations

- Acceptance coverage remains the explicit in-process instrumentation API (`explicit in-process API; no UI/model consumer`); physical-device persistence and daily-use trials are tracked separately in `#84`.
- Raw phase logs and `result.json` outputs are retained outside Git.
