package com.sdl.hellosdlandroid;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.SdlProxyALM;
import com.smartdevicelink.proxy.callbacks.OnServiceEnded;
import com.smartdevicelink.proxy.callbacks.OnServiceNACKed;
import com.smartdevicelink.proxy.interfaces.IProxyListenerALM;
import com.smartdevicelink.proxy.rpc.*;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.LockScreenStatus;
import com.smartdevicelink.proxy.rpc.enums.SdlDisconnectedReason;

public class SdlService extends Service implements IProxyListenerALM {
    //region Private static final area

    private static final String APP_NAME 				= "Hello Sdl";
	private static final String APP_ID 					= "8675309";

    //endregion

    //region Private variable area

    // static variable to hold the service instance
    private static SdlService instance;

    // variable to create and call functions of the SyncProxy
    private SdlProxyALM proxy;

    // variable used to increment correlation ID for every request sent to SYNC
    public int correlationID;

    // variable used to auto stop the service and release the blocked RFCOMM of the proxy
    private Handler connectionHandler;

    // variable to keep track if the app received the OnAppDidConnect notification
    private boolean appDidConnect;

    // variable to keep track if the app received the OnAppDidStart notification
    private boolean appDidStart;

    //endregion

    //region Service lifecycle area

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        proxy = null;
        correlationID = 0;
        connectionHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onDestroy() {
        this.disposeProxy();
        instance = null;
        super.onDestroy();
    }

    @Override
	public int onStartCommand(Intent intent, int flags, int startId) {
        this.setupProxy();

        connectionHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopSelf();
            }
        }, 180 * 1000);

		return START_STICKY;
	}

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public static SdlService getInstance() {
        return instance;
    }

    //endregion

    //region Proxy lifecycle area

    private void resetProperties() {
        this.appDidConnect = false;
        this.appDidStart = false;
    }

    public void setupProxy() {
		if (proxy == null) {
			try {
				this.proxy = new SdlProxyALM(this, APP_NAME, true, APP_ID);
                this.resetProperties();
            } catch (SdlException e) {
				e.printStackTrace();
				if (proxy == null) {
					stopSelf();
				}
			}
		}
	}

	public void disposeProxy() {
		LockScreenActivity.updateLockScreenStatus(LockScreenStatus.OFF);

		if (proxy != null) {
			try {
				proxy.dispose();
			} catch (SdlException e) {
				e.printStackTrace();
			}
			proxy = null;
		}
	}

    //endregion

    //region Request management area

    public int nextCorrelationID() {
        correlationID = (correlationID % 0xffff) + 1;
        return correlationID;
    }

	private void sendRequest(RPCRequest request) {
        // auto set a correlation id
		if (request.getCorrelationID() == null) {
			request.setCorrelationID(nextCorrelationID());
		}

        // send the actual request
		try {
			proxy.sendRPCRequest(request);
		} catch (SdlException e) {
			e.printStackTrace();
		}
	}

    //endregion

    //region App notification area

	private void onAppDidConnect() {
        Log.v("SDL", "onAppDidConnect");
	}

    private void onAppDidDisconnect() {
        Log.v("SDL", "onAppDidDisconnect");
    }

	private void onAppDidStart(boolean firstStart) {
        Log.v("SDL", "onAppDidStart. firstStart = " + (firstStart ? "yes" : "no"));

        if (firstStart) {
            Show show = new Show();
            show.setMainField1("Welcome to");
            show.setMainField2("Hello SDL");

            this.sendRequest(show);
        }
	}

	private void onAppDidStop() {
        Log.v("SDL", "onAppDidStop");
	}

    //endregion

    //region Proxy notification area

    @Override
    public void onError(String info, Exception e) {

    }

    @Override
    public void onProxyClosed(String info, Exception e, SdlDisconnectedReason reason) {
        // call the notification to prepare app disconnection
        this.onAppDidDisconnect();
        this.disposeProxy();
        this.stopSelf();
    }

    //endregion

    //region RPC notification area

    @Override
    public void onOnHMIStatus(OnHMIStatus notification) {
        // wrap logic to provide an OnAppDidConnect notification.
        // this notification is called when the app freshly connected to the head unit.
        if (this.appDidConnect == false) {
            this.appDidConnect = true;
            // just in case for the auto stop routine we should cancel it
            connectionHandler.removeCallbacksAndMessages(null);
            // call the notification
            this.onAppDidConnect();
        }

        // wrap logic to provide an OnAppDidStart notification.
        // this notification is called when the app is started by the user
        // in addition it can tell if it was the first start
        if (notification.getHmiLevel().equals(HMILevel.HMI_FULL)) {
            if (this.appDidStart == false) {
                this.appDidStart = true;
                // call the notification
                this.onAppDidStart(notification.getFirstRun());
            }
        }

        // wrap logic to provide an OnAppDidStop notification.
        // this notification is called when the app is stopped by the user
        if (notification.getHmiLevel().equals(HMILevel.HMI_NONE)) {
            if (this.appDidStart == true) {
                this.appDidStart = false;
                // call the notification
                this.onAppDidStop();
            }
        }
    }

    @Override
    public void onOnLockScreenNotification(OnLockScreenStatus notification) {
        LockScreenActivity.updateLockScreenStatus(notification.getShowLockScreen());
    }

    @Override
    public void onOnCommand(OnCommand notification){}
    @Override
    public void onOnPermissionsChange(OnPermissionsChange notification) {}
    @Override
    public void onOnVehicleData(OnVehicleData notification) {}
    @Override
    public void onOnButtonEvent(OnButtonEvent notification) {}
    @Override
    public void onOnButtonPress(OnButtonPress notification) {}
    @Override
    public void onOnTBTClientState(OnTBTClientState notification) {}
    @Override
    public void onOnAudioPassThru(OnAudioPassThru notification) {}
    @Override
    public void onOnLanguageChange(OnLanguageChange notification) {}
    @Override
    public void onOnHashChange(OnHashChange notification) {}
    @Override
    public void onOnSystemRequest(OnSystemRequest notification) {}
    @Override
    public void onOnKeyboardInput(OnKeyboardInput notification) {}
    @Override
    public void onOnTouchEvent(OnTouchEvent notification) {}
    @Override
    public void onOnStreamRPC(OnStreamRPC notification) {}
    @Override
    public void onOnDriverDistraction(OnDriverDistraction notification) {}
    @Override
    public void onServiceEnded(OnServiceEnded serviceEnded) {}
    @Override
    public void onServiceNACKed(OnServiceNACKed serviceNACKed) {}
    @Override
    public void onServiceDataACK() {}

    //endregion

    //region RPC response area

	@Override
	public void onListFilesResponse(ListFilesResponse response) {
	}

	@Override
	public void onPutFileResponse(PutFileResponse response) {
	}

	@Override
	public void onDeleteFileResponse(DeleteFileResponse response) {
	}

	@Override
	public void onSetAppIconResponse(SetAppIconResponse response) {
	}

	@Override
	public void onAddCommandResponse(AddCommandResponse response) {
	}

	@Override
	public void onSubscribeVehicleDataResponse(SubscribeVehicleDataResponse response) {
	}
	
	@Override
	public void onAddSubMenuResponse(AddSubMenuResponse response) {
	}

	@Override
	public void onCreateInteractionChoiceSetResponse(CreateInteractionChoiceSetResponse response) {
	}

	@Override
	public void onAlertResponse(AlertResponse response) {
	}

	@Override
	public void onDeleteCommandResponse(DeleteCommandResponse response) {
	}

	@Override
	public void onDeleteInteractionChoiceSetResponse(DeleteInteractionChoiceSetResponse response) {
	}

	@Override
	public void onDeleteSubMenuResponse(DeleteSubMenuResponse response) {
	}

	@Override
	public void onPerformInteractionResponse(PerformInteractionResponse response) {
	}

	@Override
	public void onResetGlobalPropertiesResponse(ResetGlobalPropertiesResponse response) {
	}

	@Override
	public void onSetGlobalPropertiesResponse(SetGlobalPropertiesResponse response) {
	}

	@Override
	public void onSetMediaClockTimerResponse(SetMediaClockTimerResponse response) {
	}

	@Override
	public void onShowResponse(ShowResponse response) {
	}

	@Override
	public void onSpeakResponse(SpeakResponse response) {
	}

	@Override
	public void onSubscribeButtonResponse(SubscribeButtonResponse response) {
	}

	@Override
	public void onUnsubscribeButtonResponse(UnsubscribeButtonResponse response) {
	}

	@Override
	public void onUnsubscribeVehicleDataResponse(UnsubscribeVehicleDataResponse response) {
	}

	@Override
	public void onGetVehicleDataResponse(GetVehicleDataResponse response) {
	}

	@Override
	public void onReadDIDResponse(ReadDIDResponse response) {
	}

	@Override
	public void onGetDTCsResponse(GetDTCsResponse response) {
	}

	@Override
	public void onPerformAudioPassThruResponse(PerformAudioPassThruResponse response) {
	}

	@Override
	public void onEndAudioPassThruResponse(EndAudioPassThruResponse response) {
	}

	@Override
	public void onScrollableMessageResponse(ScrollableMessageResponse response) {
	}

	@Override
	public void onChangeRegistrationResponse(ChangeRegistrationResponse response) {
	}

	@Override
	public void onSetDisplayLayoutResponse(SetDisplayLayoutResponse response) {
	}

	@Override
	public void onSliderResponse(SliderResponse response) {
	}

	@Override
	public void onSystemRequestResponse(SystemRequestResponse response) {
	}

	@Override
	public void onDiagnosticMessageResponse(DiagnosticMessageResponse response) {
	}

	@Override
	public void onStreamRPCResponse(StreamRPCResponse response) {
	}

	@Override
	public void onDialNumberResponse(DialNumberResponse response) {
	}

	@Override
	public void onSendLocationResponse(SendLocationResponse response) {
    }

	@Override
	public void onShowConstantTbtResponse(ShowConstantTbtResponse response) {
	}

	@Override
	public void onAlertManeuverResponse(AlertManeuverResponse response) {
	}

	@Override
	public void onUpdateTurnListResponse(UpdateTurnListResponse response) {
	}

	@Override
	public void onGenericResponse(GenericResponse response) {
	}

    //endregion
}