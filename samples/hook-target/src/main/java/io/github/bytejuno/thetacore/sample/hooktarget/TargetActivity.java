package io.github.bytejuno.thetacore.sample.hooktarget;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class TargetActivity extends Activity {
    public String probeValue() { return "ORIGINAL"; }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        TextView value = new TextView(this);
        Button refresh = new Button(this);
        refresh.setText("Read Hook result");
        // Reflection avoids Java call-site inlining hiding the demonstration hook.
        Runnable read = () -> {
            try { value.setText("Hook result: " + TargetActivity.class.getDeclaredMethod("probeValue").invoke(this)); }
            catch (Exception error) { value.setText("Probe failed: " + error.getClass().getSimpleName()); }
        };
        refresh.setOnClickListener(v -> read.run());
        root.addView(value); root.addView(refresh); setContentView(root); read.run();
    }
}
