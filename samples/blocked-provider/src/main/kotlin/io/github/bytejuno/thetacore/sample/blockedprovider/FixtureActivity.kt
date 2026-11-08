package io.github.bytejuno.thetacore.sample.blockedprovider

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Local-only controls. Intent extras, deep links and provider calls cannot release/reset gates. */
class FixtureActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() {
            status.text = FixtureRuntime.snapshot()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom)
                insets
            }
        }
        layout.addView(TextView(this).apply {
            text = "Theta blocked sources\nSelect Import data ZIP → Theta blocked sources in DocumentsUI.\n" +
                "control: immediate; delayed: 15s; blocked: Release or 120s abort.\n" +
                "prefix-control: full padded ZIP; prefix-interrupted: prefix then Reset/EIO.\n" +
                "Release sends bytes for old modes; ignored for prefix-interrupted.\n" +
                "Reset aborts reads, not client descriptors.\n" +
                "FD_RELEASE is not proof of host cancellation."
        })
        layout.addView(Button(this).apply {
            text = "Release current reads"
            contentDescription = "fixture_release"
            setOnClickListener { FixtureRuntime.releaseAll(); status.text = FixtureRuntime.snapshot() }
        })
        layout.addView(Button(this).apply {
            text = "Reset / abort current reads"
            contentDescription = "fixture_reset"
            setOnClickListener { FixtureRuntime.reset(); status.text = FixtureRuntime.snapshot() }
        })
        status = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            contentDescription = "fixture_events"
        }
        layout.addView(ScrollView(this).apply { addView(status) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
