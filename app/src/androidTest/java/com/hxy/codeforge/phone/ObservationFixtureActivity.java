package com.hxy.codeforge.phone;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;

public class ObservationFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        View view;
        if (getIntent().getBooleanExtra("blank", false)) {
            view = new View(this);
        } else {
            TextView label = new TextView(this);
            label.setText("Phone fixture 123");
            view = label;
        }
        view.setBackgroundColor(Color.BLUE);
        setContentView(view);
        for (int i = 1; i <= 20; i++) {
            view.postDelayed(() -> view.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED), i * 500L);
        }
    }
}
