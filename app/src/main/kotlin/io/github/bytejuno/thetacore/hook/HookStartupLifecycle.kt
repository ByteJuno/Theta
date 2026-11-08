package io.github.bytejuno.thetacore.hook

/** Separates an intentional stop from a failed or genuinely incomplete installation. */
internal class HookStartupLifecycle(private val journal: HookStartupJournal) {
    private var installed = false
    private var deliberatelyDisabled = false
    private var failed = false

    @Synchronized fun installed() {
        installed = true
        // An explicit disable ends a healthy run, even before the stability timer.
        // A disable during install cannot acknowledge it until install succeeds.
        if (deliberatelyDisabled && !failed) journal.stable()
    }

    @Synchronized fun disable() {
        deliberatelyDisabled = true
        if (installed && !failed) journal.stable()
    }

    @Synchronized fun stable() {
        if (installed && !failed) journal.stable()
    }

    @Synchronized fun failure() {
        failed = true
        journal.quarantine()
    }
}
