package com.sdl.hellosdlandroid;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.smartdevicelink.exception.SdlException;
import com.smartdevicelink.proxy.RPCMessage;
import com.smartdevicelink.proxy.RPCRequest;
import com.smartdevicelink.proxy.RPCResponse;
import com.smartdevicelink.proxy.SdlProxyALM;
import com.smartdevicelink.proxy.callbacks.OnServiceEnded;
import com.smartdevicelink.proxy.callbacks.OnServiceNACKed;
import com.smartdevicelink.proxy.interfaces.IProxyListenerALM;
import com.smartdevicelink.proxy.rpc.*;
import com.smartdevicelink.proxy.rpc.enums.AudioStreamingState;
import com.smartdevicelink.proxy.rpc.enums.ButtonName;
import com.smartdevicelink.proxy.rpc.enums.FileType;
import com.smartdevicelink.proxy.rpc.enums.HMILevel;
import com.smartdevicelink.proxy.rpc.enums.ImageType;
import com.smartdevicelink.proxy.rpc.enums.LockScreenStatus;
import com.smartdevicelink.proxy.rpc.enums.SdlDisconnectedReason;
import com.smartdevicelink.proxy.rpc.enums.SystemContext;
import com.smartdevicelink.proxy.rpc.enums.UpdateMode;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

public class SdlService extends Service implements IProxyListenerALM {
    //region Private static final area

    private static final String APP_NAME                 = "Hello Sdl";
    private static final String APP_ID                     = "8675309";

    //endregion

    //region Private variable area

    // variable to create and call functions of the SyncProxy
    private SdlProxyALM proxy;

    // variable used to increment correlation ID for every request sent to SYNC
    public int correlationID;

    // variable used to auto stop the service and release the blocked RFCOMM of the proxy
    private Handler connectionHandler;

    // holding pending requests to execute them sequentially
    private HashMap<Integer, RPCRequest> pendingSequentialRequests;

    // variable to keep track if the app received the OnAppDidConnect notification
    private boolean appDidConnect;

    // variable to keep track if the app received the OnAppDidStart notification
    private boolean appDidStart;

    // variable to keep track if the app icon was set
    private boolean appIconSet;

    // holding a media player for the reference audio file
    private MediaPlayer appMediaPlayer;

    // variable to keep track if the user paused playback
    private boolean appMediaPlayerUserPaused;

    // variable to keep track of the current hmi level
    private HMILevel sdlHMILevel;

    // variable to keep track of the current audio streaming state
    private AudioStreamingState sdlAudioStreamingState;

    // variable to keep track of the current system context
    private SystemContext sdlSystemContext;

    // variable to keep track if file management is supported by SDL
    private boolean sdlSupportFiles;

    // holding a list of unique names of files that exist on the remote unit
    private Set<String> sdlRemoteFiles;

    // holding pending requests of files to be uploaded or deleted
    private HashMap<Integer, String> sdlPendingRemoteFiles;

    //endregion

    //region Service lifecycle area

    public static void startService(Context context) {
        // Due to limitations of figuring out if a BluetoothDevice is actually connected
        // this method checks only if BT is enabled and at least one device is bonded/paired
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled() && adapter.getBondedDevices().size() > 0) {
            Intent intent = new Intent(context, SdlService.class);
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        proxy = null;
        correlationID = 0;
        connectionHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onDestroy() {
        this.disposeProxy();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        this.setupProxy();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    //endregion

    //region Proxy lifecycle area

    private void resetProperties() {
        this.pendingSequentialRequests = new HashMap<>(100);
        this.appDidConnect = false;
        this.appDidStart = false;
        this.appIconSet = false;
        this.appMediaPlayer = null;
        this.appMediaPlayerUserPaused = false;
        this.sdlHMILevel = null;
        this.sdlAudioStreamingState = null;
        this.sdlSystemContext = null;
        this.sdlSupportFiles = false;
        this.sdlRemoteFiles = new HashSet<>(10);
        this.sdlPendingRemoteFiles = new HashMap<>(10);
    }

    public void setupProxy() {
        if (proxy == null) {
            try {
                this.resetProperties();
                this.connectionHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        disposeProxy();
                    }
                }, 180 * 1000);
                this.proxy = new SdlProxyALM(this, APP_NAME, true, APP_ID);
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

    private void logMessage(RPCMessage message) {
        try { Log.v("SDL", message.serializeJSON((byte) 1).toString(2)); }
        catch (JSONException e) { e.printStackTrace(); }
    }

    public int nextCorrelationID() {
        correlationID = (correlationID % 0xffff) + 1;
        return correlationID;
    }

    private void handleSequentialRequestsForResponse(RPCResponse response) {
        if (response != null) {
            this.logMessage(response);

            // get the correlation id of the response
            Integer correlationID = response.getCorrelationID();
            // get a sequential request for the correlation id if any exist
            RPCRequest request = this.pendingSequentialRequests.get(correlationID);

            if (request != null) {
                // there is another requet we need to send out now
                this.pendingSequentialRequests.remove(correlationID);
                this.sendRequest(request);
            }
        }
    }

    private void sendRequest(RPCRequest request) {
        // auto set a correlation id
        if (request.getCorrelationID() == null) {
            request.setCorrelationID(nextCorrelationID());
        }

        this.logMessage(request);

        // check for remote file changes (putfile or deletefile)
        String filename = null;
        if (request instanceof PutFile) {
            filename = ((PutFile) request).getSdlFileName();
        } else if (request instanceof DeleteFile) {
            filename = ((DeleteFile) request).getSdlFileName();
        }

        // in case we are going to change the list of remote files:
        if (filename != null) {
            this.sdlPendingRemoteFiles.put(request.getCorrelationID(), filename);
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
            // the list of requests doesn't need to be performed sequentially. send all now.
            for (RPCRequest request : requests) {
                this.sendRequest(request);
            }
        }
    }

    //endregion

    //region File & image management area

    private byte[] readBytesFromResource(int resource) {
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
        PutFile request = null;

        if (this.sdlSupportFiles) {
            request = new PutFile();
            request.setBulkData(data);
            request.setSdlFileName(filename);
            request.setFileType(type);
            request.setPersistentFile(persistent);
            request.setSystemFile(system);
        }

        return request;
    }

    void sendListFiles() {
        if (this.sdlSupportFiles) {
            this.sendRequest(new ListFiles());
        }
    }

    void sendAppIcon() {
        // in case the head unit doesn't support files or the icon is already set
        if (!this.sdlSupportFiles || this.appIconSet) {
            return;
        }

        this.appIconSet = true;

        String iconName = "ic_launcher.png";

        Vector<RPCRequest> requests = new Vector<>(2);

        // did we uploaded an app icon maybe in a previous session?
        if (!this.sdlRemoteFiles.contains(iconName)) {
            // load the data of the app icon
            byte[] data = this.readBytesFromResource(R.drawable.ic_launcher);
            // build a putfile request for a persistent image (upload only once).
            PutFile putfile = this.buildPutFile(data, iconName, FileType.GRAPHIC_PNG, true, false);
            requests.add(putfile);
        }

        SetAppIcon setappicon = new SetAppIcon();
        setappicon.setSdlFileName(iconName);
        requests.add(setappicon);

        // send the requests sequentially
        this.sendRequests(requests, true);
    }

    //endregion

    //region Audio management area

    void createMediaPlayer() {
        this.appMediaPlayer = MediaPlayer.create(this, R.raw.audio_01);
        this.appMediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                setMediaClockTimer(UpdateMode.CLEAR);
            }
        });
    }

    void startMedia() {
        if (this.appMediaPlayer != null && !this.isMediaPlaying()) {
            this.appMediaPlayer.start();
            this.appMediaPlayerUserPaused = false;

            this.setMediaClockTimer(UpdateMode.COUNTUP);
        }
    }

    void stopMedia() {
        if (this.appMediaPlayer != null && this.isMediaPlaying()) {
            this.appMediaPlayer.pause();
            this.appMediaPlayerUserPaused = false;

            this.setMediaClockTimer(UpdateMode.CLEAR);
        }
    }

    void pauseMedia(boolean userPaused) {
        if (this.appMediaPlayer != null && this.isMediaPlaying()) {
            this.appMediaPlayer.pause();
            this.appMediaPlayerUserPaused = userPaused;

            this.setMediaClockTimer(UpdateMode.PAUSE);
        }
    }

    void setMediaClockTimer(UpdateMode updateMode) {
        switch (updateMode) {
            case COUNTUP: {
                final int SECOND = 1000;
                final int MINUTE = 60 * SECOND;
                final int HOUR = 60 * MINUTE;

                int position = this.appMediaPlayer.getCurrentPosition();
                int positionHour = position / HOUR;
                int positionMinute = (position % HOUR) / MINUTE;
                int positionSecond = (position % MINUTE) / SECOND;

                int duration = this.appMediaPlayer.getDuration();
                int durationHour = duration / HOUR;
                int durationMinute = (duration % HOUR) / MINUTE;
                int durationSecond = (duration % MINUTE) / SECOND;

                StartTime startTime = new StartTime();
                startTime.setHours(positionHour);
                startTime.setMinutes(positionMinute);
                startTime.setSeconds(positionSecond);

                StartTime endTime = new StartTime();
                endTime.setHours(durationHour);
                endTime.setMinutes(durationMinute);
                endTime.setSeconds(durationSecond);

                SetMediaClockTimer timer = new SetMediaClockTimer();
                timer.setUpdateMode(UpdateMode.COUNTUP);
                timer.setStartTime(startTime);
                timer.setEndTime(endTime);
                this.sendRequest(timer);

                Show show = new Show();
                show.setMainField3("Playing");
                this.sendRequest(show);
                break;
            }
            case RESUME: {
                SetMediaClockTimer timer = new SetMediaClockTimer();
                timer.setUpdateMode(UpdateMode.RESUME);
                this.sendRequest(timer);

                Show show = new Show();
                show.setMainField3("Playing");
                this.sendRequest(show);
                break;
            }
            case CLEAR: {
                SetMediaClockTimer timer = new SetMediaClockTimer();
                timer.setUpdateMode(UpdateMode.CLEAR);
                this.sendRequest(timer);

                Show show = new Show();
                show.setMainField3("Stopped");
                this.sendRequest(show);
                break;
            }
            case PAUSE: {
                SetMediaClockTimer timer = new SetMediaClockTimer();
                timer.setUpdateMode(UpdateMode.PAUSE);
                this.sendRequest(timer);

                Show show = new Show();
                show.setMainField3("Paused");
                this.sendRequest(show);
                break;
            }
        }
    }

    boolean isMediaPlaying() {
        return this.appMediaPlayer != null && this.appMediaPlayer.isPlaying();
    }

    boolean isMediaPausedByUser() {
        return this.appMediaPlayer != null && this.appMediaPlayerUserPaused;
    }

    //endregion

    //region App notification area

    private void onAppDidConnect() {
        Log.v("SDL", "onAppDidConnect");
        this.createMediaPlayer();

        this.sendListFiles();
    }

    private void onAppDidDisconnect() {
        Log.v("SDL", "onAppDidDisconnect");

        // audio playback requirements: phase 1
        this.stopMedia();
    }

    private void onAppDidStart(boolean firstStart) {
        Log.v("SDL", "onAppDidStart. firstStart = " + (firstStart ? "yes" : "no"));

        if (firstStart) {
            // lets subscribe to all buttons
            SubscribeButton button = new SubscribeButton();
            button.setButtonName(ButtonName.OK);
            this.sendRequest(button);
            button.setButtonName(ButtonName.SEEKLEFT);
            this.sendRequest(button);
            button.setButtonName(ButtonName.SEEKRIGHT);
            this.sendRequest(button);

            String imageName = "sdl_icon.png";
            Image image = new Image();
            image.setImageType(ImageType.DYNAMIC);
            image.setValue(imageName);
            
            Show show = new Show();
            show.setMainField1("Welcome to");
            show.setMainField2("Hello SDL");

            if (this.sdlSupportFiles) {
                if (this.sdlRemoteFiles.contains(imageName)) {
                    // if the image is already available then use it immediately
                    show.setGraphic(image);

                    // add the show which will also show the graphic
                    this.sendRequest(show);
                } else {
                    // the image does not exist now. we need to send a show without graphic
                    // and a putfile after that for the graphic. After the putfile another Show follows.

                    // send the first show
                    this.sendRequest(show);

                    // create the putfile
                    byte[] data = this.readBytesFromResource(R.drawable.sdl_icon);
                    PutFile putfile = this.buildPutFile(data, imageName, FileType.GRAPHIC_PNG, false, false);

                    // create the second show
                    Show showimage = new Show();
                    showimage.setGraphic(image);

                    // create a list for the putfile and show (with graphic only).
                    // the list is performed sequentially. The show waits until the graphic is done.
                    Vector<RPCRequest> requests = new Vector<>();
                    requests.add(putfile);
                    requests.add(showimage);
                    this.sendRequests(requests, true);
                }
            } else {
                this.sendRequest(show);
            }
        }
    }

    private void onAppDidStop() {
        Log.v("SDL", "onAppDidStop");
    }

    private void onHMILevelChange(HMILevel hmiLevel) {
        Log.v("SDL", "onAppHMILevelChange: " + hmiLevel.toString());

        // audio playback requirements: phase 1
        switch (hmiLevel) {
            case HMI_FULL:
                this.setMediaClockTimer(UpdateMode.COUNTUP);
                break;
            case HMI_BACKGROUND:
            case HMI_NONE:
                this.stopMedia();
                break;
        }
    }

    private void onAudioStreamingStateChange(AudioStreamingState audioStreamingState) {
        Log.v("SDL", "onAppAudioStreamingStateChange: " + audioStreamingState.toString());

        // audio playback requirements: phase 3
        if (audioStreamingState.equals(AudioStreamingState.NOT_AUDIBLE)) {
            if (this.isMediaPlaying()) {
                this.pauseMedia(false);

            }
        } else {
            if (!this.isMediaPlaying()) {
                if (!this.isMediaPausedByUser()) {
                    this.startMedia();
                }
            }
        }
    }

    private void onSystemContextChange(SystemContext systemContext) {
        Log.v("SDL", "onAppSystemContextChange: " + systemContext.toString());
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
    }

    //endregion

    //region RPC notification area

    @Override
    public void onOnHMIStatus(OnHMIStatus notification) {
        // wrap logic to provide changes on hmi level
        if (!notification.getHmiLevel().equals(this.sdlHMILevel)) {
            // call the notification because hmi level has changed
            this.onHMILevelChange(notification.getHmiLevel());
            this.sdlHMILevel = notification.getHmiLevel();
        }

        // wrap logic to provide changes on audio streaming state
        if (!notification.getAudioStreamingState().equals(this.sdlAudioStreamingState)) {
            // call the notification because audio streaming state has changed
            this.onAudioStreamingStateChange(notification.getAudioStreamingState());
            this.sdlAudioStreamingState = notification.getAudioStreamingState();
        }

        // wrap logic to provide changes on system context
        if (!notification.getSystemContext().equals(this.sdlSystemContext)) {
            // call the notification becase system context has changed
            this.onSystemContextChange(notification.getSystemContext());
            this.sdlSystemContext = notification.getSystemContext();
        }

        // wrap logic to provide an OnAppDidConnect notification.
        // this notification is called when the app freshly connected to the head unit.
        if (this.appDidConnect == false) {
            this.appDidConnect = true;
            // the connection handler must be stoped. remove all callbacks
            connectionHandler.removeCallbacksAndMessages(null);
            // prepare sdl based parameters
            try { this.sdlSupportFiles = proxy.getDisplayCapabilities().getGraphicSupported(); }
            catch (SdlException e) { e.printStackTrace(); }

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
    public void onOnButtonPress(OnButtonPress notification) {
        if (notification.getButtonName().equals(ButtonName.OK)) {
            // audio playback requirements: phase 2
            if (this.isMediaPlaying()) {
                this.pauseMedia(true);
            } else {
                this.startMedia();
            }
        }
    }

    @Override
    public void onOnButtonEvent(OnButtonEvent notification) {}
    @Override
    public void onOnCommand(OnCommand notification){}
    @Override
    public void onOnPermissionsChange(OnPermissionsChange notification) {}
    @Override
    public void onOnVehicleData(OnVehicleData notification) {}
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
                this.sdlRemoteFiles = new HashSet<>(response.getFilenames());
            } else {
                this.sdlRemoteFiles = new HashSet<>(10);
            }
        }
        
        this.sendAppIcon();

        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onPutFileResponse(PutFileResponse response) {
        String filename = this.sdlPendingRemoteFiles.get(response.getCorrelationID());

        if (filename != null) {
            this.sdlPendingRemoteFiles.remove(response.getCorrelationID());
            if (response.getSuccess()) {
                this.sdlRemoteFiles.add(filename);
            }
        }

        this.handleSequentialRequestsForResponse(response);
    }

    @Override
    public void onDeleteFileResponse(DeleteFileResponse response) {
        String filename = this.sdlPendingRemoteFiles.get(response.getCorrelationID());

        if (filename != null) {
            this.sdlPendingRemoteFiles.remove(response.getCorrelationID());
            if (response.getSuccess()) {
                this.sdlRemoteFiles.remove(filename);
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
