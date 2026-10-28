package id.poncol.tvcompanion.airplaylab;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Native networking/decoding and its Surface remain entirely in :airplay. */
public final class ReceiverActivity extends Activity implements SurfaceHolder.Callback {
    private ReceiverEngine engine;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout frame = new FrameLayout(this);
        SurfaceView video = new SurfaceView(this);
        video.getHolder().addCallback(this);
        frame.addView(video, new FrameLayout.LayoutParams(-1, -1));
        status = new TextView(this); status.setText(R.string.starting);
        status.setTextSize(18); status.setTextColor(0xffffffff); status.setBackgroundColor(0xaa000000);
        status.setPadding(24, 12, 24, 12);
        FrameLayout.LayoutParams label = new FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        frame.addView(status, label); setContentView(frame);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override public void surfaceCreated(SurfaceHolder holder) {
        engine = new UxPlayEngine(this);
        engine.start(holder.getSurface(), state -> {
            int label;
            switch (state) {
                case DISCOVERABLE: label = R.string.discoverable; break;
                case CONNECTED: label = R.string.connected; break;
                case VIDEO_OUTPUT: label = R.string.video_output; break;
                case DISCONNECTED: label = R.string.disconnected; break;
                case FAILED: label = R.string.failed; break;
                default: label = R.string.starting;
            }
            status.setText(getString(R.string.receiver_status, getString(label)));
        });
    }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { stop(); }
    private void stop() { if (engine != null) { engine.close(); engine = null; } }
    @Override protected void onPause() { stop(); super.onPause(); finish(); }
    @Override protected void onDestroy() { stop(); super.onDestroy(); }
}
