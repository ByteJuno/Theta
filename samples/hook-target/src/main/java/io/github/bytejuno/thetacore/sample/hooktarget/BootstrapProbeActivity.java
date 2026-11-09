package io.github.bytejuno.thetacore.sample.hooktarget;

import android.app.Activity;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Process;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;

public final class BootstrapProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String authority = "io.github.bytejuno.thetacore.XposedService";
        AtomicInteger transactions = new AtomicInteger();
        Binder untrusted = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
                transactions.incrementAndGet();
                return false;
            }
        };
        Bundle extras = new Bundle();
        extras.putBinder("binder", untrusted);
        StringBuilder result = new StringBuilder("Bootstrap probe UID: ").append(Process.myUid());
        int rejected = 0;
        for (int index = 0; index < 3; index++) {
            try {
                if (index == 0) getContentResolver().call(Uri.parse("content://" + authority), "SendBinder", null, extras);
                else if (index == 1) getContentResolver().call(authority, "SendBinder", null, extras);
                else getContentResolver().call(authority, "SendBinder", null, null);
                result.append("\nCase ").append(index + 1).append(": NOT REJECTED");
            } catch (SecurityException expected) {
                rejected++;
                result.append("\nCase ").append(index + 1).append(": SecurityException");
            } catch (RuntimeException error) {
                result.append("\nCase ").append(index + 1).append(": ").append(error.getClass().getSimpleName());
            }
        }
        result.append("\nFake Binder transactions: ").append(transactions.get());
        result.append("\nBootstrap rejection: ").append(rejected == 3 && transactions.get() == 0 ? "PASS" : "FAIL");
        TextView view = new TextView(this);
        view.setText(result);
        view.setTextSize(20);
        view.setPadding(24, 24, 24, 24);
        setContentView(view);
    }
}
