# Issue 39 verification

Implementation `e974bbdb2c49b8480fdc7f2368e9afd6384ed620` was tested on Windows with JDK 25. The Debug APK SHA-256 was `6f3b229760d870e980c56835c9982193c4cc166e926c293397aeedeace4f95b8`.

On the original implementation, both new behavioral assertions reproduced the incorrect truncation message. On the implementation above, all 42 targeted JVM tests passed with zero skips. The same Gradle invocation assembled Debug and stored the configuration cache with configuration-cache problems treated as failures (69.33 seconds).

The run-loop tests checked the repeated-call limit in history and two client calls in a paused turn in the next model request. Both paths retained `CANCELLED` and `NONE`. Tests also covered actual truncation, interrupted registration and reopen, old records without a reason, new record round trips, and streamed checkpoint serialization. Raw failure and success logs are retained outside Git.

No device, live provider, visual, Release/R8 or Android API 33 runtime test was performed locally. Final-head CI and independent regression belong to the reviewer.
