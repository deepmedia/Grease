package io.deepmedia.tools.grease.sample;

import android.app.Application;
import android.util.Log;

class SampleApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("SampleApplication", "Something.");
    }
}
