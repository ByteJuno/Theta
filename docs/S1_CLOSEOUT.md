# S1 closeout physical validation — 2026-11-09

The listed bootstrap, Hook authorization, disable, target recovery and CI-pair frame checks have run on the identified PLK110. Historical emulator/runtime acceptance retains its original candidate identities. A new complete A–K matrix and release readiness are not asserted.

## Frozen identities and evidence

| Input | Identity |
| --- | --- |
| Host build source | `09f233da7d30f369cb8a5cca14d053747ceab4bb` |
| Physical Host SHA-256 | `bafeae5ddf2cd1073afba0538c9496b65f14f1512870b13d20e4337bb341d6ed` |
| Dedicated Target SHA-256 | `4da24b1790c3b76a1086e9573960dfbbad20cac983ef435ed8e1436d378c11ad` |
| Host/Target signer SHA-256 | `0a03e9518759eb79fcb57b6264010ed1eff1193d5eea23facfbc75378b2ddf6b` |
| Normal payload SHA-256 | `673934a00f1be3e96f0f958ebeb9edb778a350d54aca416b9fb76bb6689f30ef` |
| Crash v3 SHA-256 | `038c4897707ac259ddd26ab1fc289279ed299a950f8b4d6f2c7553355bd8e75a` |
| Recovery v4 SHA-256 | `fb56c28ec84ff76e6819193bbd5bb3aec5e0aabad804539cafab946277d66773` |
| Fault-payload signer SHA-256 | `396eb8e48584e743f151c41c5a490793a5c84d2be8b2bebd7062b6e2edd8946a` |

The fault payloads share their existing dedicated Hook ID and signer; versions 3/4 create fresh version-keyed journals without clearing data. Their signer differs from the normal payload and Host. Installed base APK hashes were checked before regression and again after restoring the Host from the performance experiment.

The [evidence index](evidence/s1-closeout-20261109.json) contains 15 bounded cases, 39 local screenshot hashes, crash/recovery/outage observations, final state, CI inputs and raw-frame dump hashes. The [frame CSV](evidence/s1-physical-frames-20261109.csv) preserves all 2,880 selected samples. Local screenshots/command logs remain under `theta-physical-acceptance-20261109T015918Z/s1-closeout-live`; they are not uploaded as public artifacts.

## Bootstrap boundary

The published provider now checks the original Binder caller UID before forwarding any call or reading Binder extras. Only exact root UID 0 and platform system UID 1000 may reach the unchanged official libxposed provider delegate. The library's independently exported provider is removed from the merged manifest, preserving its authority on the gated provider instead.

This authenticates a platform-privileged bootstrap origin. It does not identify a particular framework among other root/system software or defend against a compromised OS/root. Framework name, version, capability flags and Binder descriptors are not used as identity proof. Scope approval remains the framework's normal notification callback; this change does not grant scope.

Three JVM tests cover rejection without delegate execution, exact privileged forwarding, and delegate failure without fallback. The dedicated Target's ordinary UID 10449 produced three SecurityException results through URI, authority and null-extras calls; its fake Binder received zero transactions. The Host remained connected afterward.

## New-candidate Hook results

All rows belong to the frozen `bafeae5d…d6ed` Host. Older `0139f91a…fb91c9` results in `HOOK_COMPAT_VALIDATION.md` remain separate.

| Case | Observed result | Local evidence IDs |
| --- | --- | --- |
| System document picker | Normal fixture ID, dedicated Target and verified signer reviewed | 22–23, 55–56 |
| Unhandled scope request | Awaiting decision, then 60,000ms timeout; cold Target ORIGINAL, PID 19274 | 24, 26–29 |
| Timeout then real Allow | Host stays timed out; no new payload; cold Target ORIGINAL, PID 25697 | 32–34 |
| Real Reject | Target scope not approved; no new payload; cold Target ORIGINAL, PID 12624 | 57, 61–63 |
| Swipe notification away | Callback reports not approved before deadline; cold Target ORIGINAL, PID 16553 | 64, 66–68 |
| Real Allow | Publishes new remote payload; cold Target HOOKED (ORIGINAL), PID 18262 | 69, 71–73 |
| Disable in the same process | Explicit Target Read returns ORIGINAL; PID remains 18262 | 74–77 |
| Existing scope reuse | New payload without a new scope notification; cold Target HOOKED, PID 20821 | 78–80 |
| Disable while awaiting, then Allow | Hook action superseded; confirm again; payload unchanged; cold Target ORIGINAL, PID 28130 | 87–92 |
| Request UI leaves composition, then Allow | Old task 34872 finishes, new task 34885; composition cancellation, no republish; cold Target ORIGINAL, PID 2952 | 97, 99–101 |

Manager scope views were cold-opened after external approvals to avoid cached checkboxes. Only Theta's dedicated Target scope changed; the module switch remained on. Manager checkboxes were never substituted for real notification callbacks.

The pre-positive remote payload remained `dad9af00…`; real Allow replaced it with `bf735e82…`, scope reuse replaced that with `cd51d641…`, and subsequent cancellation cases left `cd51d641…` unchanged. The normal remote APK hash matched the frozen payload. These results cover the dedicated sample and visible/remote-file behavior, not arbitrary targets or a direct dump of every remote preference.

## Crash quarantine, version recovery and external rescue

The fresh v3 journal was absent before execution. Three starts naturally died with `THETA_S1_DEDICATED_TARGET_CRASH`, with exception PIDs 7841, 8327 and 8545 and journal counts 1, 2 and 3. No external kill created those crashes. The second immediate pidof briefly listed an extra process; the fatal-exception PID was 8327 and both listed processes disappeared.

The fourth start, PID 8678, stayed alive for 35.38 seconds, produced no new fixture exception, retained count 3, and displayed ORIGINAL. The immutable process/journal result ends at UI_PENDING; screenshot 107 separately completes its visual check.

The same-signer v4 payload published successfully. Cold PID 11481 displayed RECOVERY_HOOKED (ORIGINAL); after more than 46 seconds it was still the same PID, the v4 journal was 0, and the old v3 journal remained 3. No journal or application data was reset.

With recovery active, the Host was force-stopped and remained absent. A cold manager view revoked only the dedicated Target scope; cold Target PID 14730 displayed ORIGINAL while the Host was still absent. This establishes the manager scope-removal rescue path. Whole-framework shutdown and uninstall alternatives were not executed on the personal phone.

## CI-bound physical frame measurement

[CI run 42599041866](https://github.com/ByteJuno/Theta/actions/runs/42599041866), head `09f233d`, artifact `13664838517` supplies the pair. Independent verification found only `app/proguard-rules.pro` different, exactly seven shared-library keep rules different in effective R8 configurations, and identical runtime dependency trees.

| CI original | Bytes | SHA-256 |
| --- | ---: | --- |
| Production | 14,084,636 | `9445892ab875884ebc458608951f8d85ff98e0ac9ea313cdfb261f25acedfcb3` |
| Stripped comparison | 2,637,108 | `e89ad252a508bebaa7691a578d438b9f9deb9273af329c28c44b165c898dae30` |

The CI size difference is **11,447,528 bytes**. The stripped comparison is not a plugin-compatible release. Both APKs were locally test-signed with the existing Host certificate; every non-signature ZIP entry remained unchanged. Installed SHA-256 values were respectively `caf7ce1688147a13d816de02b0d28f0af01b628fc208011bb7570aab40ef0dc9` and `7a243e073dfccd3eb1be2017730852022535515e9cb947bcc1c88d6371ca3f20`. CI-original and locally signed package sizes/hashes are recorded separately.

The physical display was 1272×2772 at nominal 120Hz, DPI 560. Each variant cold-opened the same empty Host home, performed two warm-up drags, then six alternating 800ms drags, resetting gfxinfo per drag and waiting 1,000ms before capture. A/B/B/A order produced six 120-frame tail samples per run. Flags must be zero; the metric is `FrameCompleted - IntendedVsync`. It samples frame duration, not end-to-end input/panel latency.

| Order | Variant | Valid frames | Mean ms | P95 ms | P99 ms |
| --- | --- | ---: | ---: | ---: | ---: |
| A1 | Production | 720 | 3.926 | 5.089 | 6.317 |
| B1 | Comparison | 720 | 3.651 | 4.396 | 5.151 |
| B2 | Comparison | 720 | 3.899 | 4.883 | 6.503 |
| A2 | Production | 720 | 3.802 | 4.944 | 5.897 |
| Pooled A | Production | 1,440 | 3.864 | 5.029 | 6.155 |
| Pooled B | Comparison | 1,440 | 3.775 | 4.686 | 6.178 |

Pooled mean difference is +0.089ms; P95 difference is +0.343ms. Both pools had zero sampled frame-deadline misses. Independent parsing checked all 24 raw dumps, 2,880 unique timestamps later than their corresponding reset, and reproduced mean/P95. Run-to-run variation is material; two repetitions on one phone do not establish statistical significance or an acceptable-overhead threshold.

The system reported 120Hz before/after each run; per-frame intervals are in the CSV. Brightness stayed at the same raw value, thermal status stayed 0, and battery temperature moved from 20.7°C to 21.2°C. GPU/CPU frequency and all background load were not locked. The first attempt requesting 60Hz failed before accepting samples because the device still reported 120Hz; that failure is retained locally and excluded here.

The exact authenticated Host and original display preference were restored. Final cold Target PID 31531 was ORIGINAL, global Hook was explicitly disabled, the Theta module remained on, its dedicated Target scope was unchecked, there was no Theta scope notification, and only display 0 remained.

## Other validation and limits

Local source checks: app 406 tests (405 passed, one existing platform skip), agent-core 162 passed, SDK task with no cases. Total 568, 567 passed/one skipped/zero failures. Release/sample builds and lintRelease (zero errors, 15 warnings) passed. The merged manifest contains only TrustedXposedProvider at the bootstrap authority. This documentation/evidence update does not change those build inputs.

An adapted, pinned scrcpy backend rendered a separate screen and accepted scrolling/search while the main screen was awake. Main focus stayed on display 0 in bracketed launch/scroll checks. Screen-off input, independent IME behavior and system notification isolation were not established. The user subsequently explicitly authorized the main screen, where the authorization and G measurements above ran. All virtual-display backends were stopped; no helper app, extra Android permission or unrelated module/scope was installed or changed. Other firmware, arbitrary plugins and whole-framework rescue alternatives retain their disclosed limits.
