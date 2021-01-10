package io.deepmedia.tools.grease.sample;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

class SampleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample_layout);
        Log.i("SampleActivity", "Something.");
    }
}
