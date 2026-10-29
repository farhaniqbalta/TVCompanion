package id.poncol.tvcompanion.airplaylab;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Development entry point. This process never loads native receiver code. */
public final class LabActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER);
        page.setPadding(48, 32, 48, 32);
        TextView title = new TextView(this);
        title.setText(R.string.title); title.setTextSize(30);
        page.addView(title);
        TextView details = new TextView(this);
        details.setText(R.string.details);
        details.setTextSize(18); details.setPadding(0, 24, 0, 24); page.addView(details);
        Button open = new Button(this); open.setText(R.string.open_receiver);
        open.setOnClickListener(v -> startActivity(new Intent().setClassName(getPackageName(), "id.poncol.tvcompanion.airplay.runtime.AirPlayPlaybackActivity")));
        page.addView(open);
        Button licenses = new Button(this); licenses.setText(R.string.licenses);
        licenses.setOnClickListener(v -> {
            String text;
            try (java.io.InputStream input = getAssets().open("third-party-notices.txt")) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] block = new byte[8192]; int count;
                while ((count = input.read(block)) != -1) out.write(block, 0, count);
                text = out.toString("UTF-8");
            } catch (java.io.IOException e) { text = getString(R.string.license_missing); }
            new android.app.AlertDialog.Builder(this).setTitle(R.string.licenses)
                .setMessage(text).setPositiveButton(android.R.string.ok, null).show();
        });
        page.addView(licenses); setContentView(page); open.requestFocus();
    }
}
