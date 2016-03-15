package com.sdl.hellosdlandroid;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;

public class SdlReceiver  extends BroadcastReceiver {		
	public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
		if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
			SdlService serviceInstance = SdlService.getInstance();
			if (serviceInstance == null) {
				Application app = SdlApplication.getInstance();
				Intent startIntent = new Intent(app, SdlService.class);
				context.startService(startIntent);
			}
		} else if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(action)) {
			// signal your service to stop playback
		}
	}
}