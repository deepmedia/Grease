package io.deepmedia.tools.grease.sample.library;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

class SampleActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.sample_layout);
//        int abcActionBarTitleItem = androidx.appcompat.R.layout.abc_action_bar_title_item;
        Log.i("SampleActivity", "Something.");
    }
}
