# Issue 66 — durable quarantine of damaged result content

Tested implementation 6251eba1f0271b781d4e807e30068e482998cd99 on Windows with bundled SQLite, based on main 48c15591334384d6d0091c8d0fe269f65e32ece6.

All 106 targeted JVM tests passed in 23.28 seconds. Zero failures, errors or skips. Gradle reused the configuration cache under strict configuration-cache checking. Suites covered session blob reads, blob regression, SQLite metadata projections and live reads, publication and rollback, open failures, and the v1 history migration. The v2 and v3 migration cases ran in SessionBlobTest.

The v3 migration test reopened a ledger with a missing chunk, wrong size, or changed bytes. Each damaged result returned the public unavailable error, a second session retained its healthy bytes, and the owning record retained its unknown effect, exclusive resource isolation, and approval receipt. Duplicate publication and uncertain-commit tests retained quarantine. Restoring the damaged chunk directly after a rolled-back publication still left the content quarantined on reopen. Ledger-integrity checks continued to reject damaged invocation metadata.

External results and XML are in validation-issue11/issue66-b5a4057e-final. APK not generated. No device run. Android runtime migration, final-head CI and prescribed emulator regression remain for independent review.
