package com.sdl.hellosdlandroid;

import android.app.Application;
import android.content.Intent;

public class SdlApplication extends Application{
    private static SdlApplication instance;

    public static SdlApplication getInstance() {
        return instance;
    }
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        LockScreenActivity.registerActivityLifecycle(this);

        if (SdlService.getInstance() == null) {
            Intent intent = new Intent(this, SdlService.class);
            startService(intent);
        }
    }
}
