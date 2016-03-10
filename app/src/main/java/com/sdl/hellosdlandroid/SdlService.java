package com.sdl.hellosdlandroid;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.SdlProxyALM;
import com.smartdevicelink.proxy.callbacks.OnServiceEnded;
import com.smartdevicelink.proxy.callbacks.OnServiceNACKed;
import com.smartdevicelink.proxy.interfaces.IProxyListenerALM;
import com.smartdevicelink.proxy.rpc.*;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.LockScreenStatus;
import com.smartdevicelink.proxy.rpc.enums.SdlDisconnectedReason;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SdlService extends Service implements IProxyListenerALM {
    //region Private static final area

    private static final String APP_NAME                 = "Hello Sdl";
    private static final String APP_ID                     = "8675309";

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
    
    // holding pending requests to execute them sequentially
    private HashMap<Integer, RPCRequest> pendingSequentialRequests;

    // holding pending requests of files to be uploaded or deleted
    private HashMap<Integer, String> pendingRemoteFiles;

    // holding a list of unique names of files that exist on the remote unit
    private Set<String> remoteFiles;
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
        this.pendingSequentialRequests = new HashMap<>(100);
        this.pendingRemoteFiles = new HashMap<>(10);
        this.remoteFiles = new HashSet<>(10);
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

    private void handleSequentialRequestsForResponse(RPCResponse response) {
        if (response != null) {
            Integer correlationID = response.getCorrelationID();
            RPCRequest request = this.pendingSequentialRequests.get(correlationID);

            if (request != null) {
                this.sendRequest(request);
            }
        }
    }

    private void sendRequest(RPCRequest request) {
        // auto set a correlation id
        if (request.getCorrelationID() == null) {
            request.setCorrelationID(nextCorrelationID());
        }

        // check for remote file changes (putfile or deletefile)
        String filename = null;
        if (request instanceof PutFile) {
            filename = ((PutFile) request).getSdlFileName();
        } else if (request instanceof DeleteFile) {
            filename = ((DeleteFile) request).getSdlFileName();
        }

        // in case we are going to change the list of remote files:
        if (filename != null) {
            this.pendingRemoteFiles.put(request.getCorrelationID(), filename);
        }

        // send the actual request
        try {
            proxy.sendRPCRequest(request);
        } catch (SdlException e) {
            e.printStackTrace();
        }
    }

    private void sendRequests(List<RPCRequest> requests, boolean sequential) {
        if (requests == null || requests.size() == 0) {
            return;
        }

        if (sequential) {
            for (int i = 0; i < requests.size() - 1; i++) {
                // get the request to that a sequential request should be performed
                RPCRequest request = requests.get(i);
                // the next request that has to be performed after the current one
                RPCRequest next = requests.get(i+1);

                // specify the correlation ID for the request
                request.setCorrelationID(nextCorrelationID());

                // use this correlation id to send the next one
                this.pendingSequentialRequests.put(request.getCorrelationID(), next);
            }

            this.sendRequest(requests.get(0));
        } else {
            for (RPCRequest request : requests) {
                this.sendRequest(request);
            }
        }
    }

    //endregion

    //region File & image management area

    private byte[] readResourceData(int resource) {
        InputStream is = null;
        try {
            is = getResources().openRawResource(resource);
            ByteArrayOutputStream os = new ByteArrayOutputStream(is.available());
            final int bufferSize = 4096;
            final byte[] buffer = new byte[bufferSize];
            int available;
            while ((available = is.read(buffer)) >= 0) {
                os.write(buffer, 0, available);
            }
            return os.toByteArray();
        } catch (IOException e) {
            Log.w("SDL Service", "Can't read icon file", e);
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    PutFile buildPutFile(byte[] data, String filename, FileType type, boolean persistent, boolean system) {
        boolean graphicSupported = false;
        PutFile request = null;

        try {
            graphicSupported = proxy.getDisplayCapabilities().getGraphicSupported();
        } catch (SdlException e) {
            e.printStackTrace();
        }

        if (graphicSupported) {
            request = new PutFile();
            request.setBulkData(data);
            request.setSdlFileName(filename);
            request.setFileType(type);
            request.setPersistentFile(persistent);
            request.setSystemFile(system);
        }

        return request;
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
        if(response.getSuccess()) {
            if (response.getFilenames() != null) {
                this.remoteFiles = new HashSet<>(response.getFilenames());
            } else {
                this.remoteFiles = new HashSet<>(10);
            }
        }

        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onPutFileResponse(PutFileResponse response) {
        String filename = this.pendingRemoteFiles.get(response.getCorrelationID());

        if (filename != null) {
            this.pendingRemoteFiles.remove(response.getCorrelationID());
            if (response.getSuccess()) {
                this.remoteFiles.add(filename);
            }
        }

        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onDeleteFileResponse(DeleteFileResponse response) {
        String filename = this.pendingRemoteFiles.get(response.getCorrelationID());

        if (filename != null) {
            this.pendingRemoteFiles.remove(response.getCorrelationID());
            if (response.getSuccess()) {
                this.remoteFiles.remove(filename);
            }
        }

        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSetAppIconResponse(SetAppIconResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onAddCommandResponse(AddCommandResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSubscribeVehicleDataResponse(SubscribeVehicleDataResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }
    @Override
    public void onAddSubMenuResponse(AddSubMenuResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onCreateInteractionChoiceSetResponse(CreateInteractionChoiceSetResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onAlertResponse(AlertResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onDeleteCommandResponse(DeleteCommandResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onDeleteInteractionChoiceSetResponse(DeleteInteractionChoiceSetResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onDeleteSubMenuResponse(DeleteSubMenuResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onPerformInteractionResponse(PerformInteractionResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onResetGlobalPropertiesResponse(ResetGlobalPropertiesResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSetGlobalPropertiesResponse(SetGlobalPropertiesResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSetMediaClockTimerResponse(SetMediaClockTimerResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onShowResponse(ShowResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSpeakResponse(SpeakResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSubscribeButtonResponse(SubscribeButtonResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onUnsubscribeButtonResponse(UnsubscribeButtonResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onUnsubscribeVehicleDataResponse(UnsubscribeVehicleDataResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onGetVehicleDataResponse(GetVehicleDataResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onReadDIDResponse(ReadDIDResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onGetDTCsResponse(GetDTCsResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onPerformAudioPassThruResponse(PerformAudioPassThruResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onEndAudioPassThruResponse(EndAudioPassThruResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onScrollableMessageResponse(ScrollableMessageResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onChangeRegistrationResponse(ChangeRegistrationResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSetDisplayLayoutResponse(SetDisplayLayoutResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSliderResponse(SliderResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSystemRequestResponse(SystemRequestResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onDiagnosticMessageResponse(DiagnosticMessageResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onStreamRPCResponse(StreamRPCResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onDialNumberResponse(DialNumberResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onSendLocationResponse(SendLocationResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onShowConstantTbtResponse(ShowConstantTbtResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onAlertManeuverResponse(AlertManeuverResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onUpdateTurnListResponse(UpdateTurnListResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onGenericResponse(GenericResponse response) {
        this.handleSequentialRequestsForResponse(response);
    }

    //endregion
}
