package com.sdk.agam.eventtracker;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ArrayBlockingQueue;


public class EventTracker extends ContextWrapper {
    private static final String TAG = "EventTracker";
    private String apiKey;
    private String deviceUID;
    private volatile ArrayBlockingQueue<EventMessage> eventQueue;
    private Handler mainHandler;
    private HandlerThread mainHandlerThread = null;
    private Boolean lastNetworkState = null;
    public Boolean debugMode;

    // Constants
    private static final Integer APIKEY_LENGTH = 16;
    private static final Integer DEVICEUID_LENGTH = 16;
    private static final Integer EVENTQUEUE_SIZE = 10;
    private static final Integer FLUSH_INTERVAL_MS = 10000;
    private static String API_ENDPOINT_URL = "https://webhook.site/08ac3515-63a1-49a2-89ef-8f106aa8e80c";

    public EventTracker(Context base, Boolean debugMode) {
        super(base);
        if(debugMode == null) {
            debugMode = BuildConfig.DEBUG;
        }
        // Note: actually use this in the log messages (TODO)
        this.debugMode = debugMode;
    }


    /**
     * Initialize the SDK with the basic tracking identifiers.
     * In addition, check for valid parameter usages.
     *
     * @param apiKey    the apiKey used for identifying SDK usage (user, ratelimit etc...)
     * @param deviceUID the specific user unique identifier we will event track
     */
    public void init(String apiKey, String deviceUID) {

        if (apiKey == null || apiKey.length() != APIKEY_LENGTH) {
            throw new IllegalArgumentException("apiKey must be a valid 16 character string");
        }

        if (deviceUID == null || deviceUID.length() != DEVICEUID_LENGTH) {
            throw new IllegalArgumentException("deviceUID must be a valid 16 character string -> " + deviceUID);
        }

        this.apiKey = apiKey;
        this.deviceUID = deviceUID;

        // We are using an ArrayBlockingQueue as it is -
        // more performance aware because of the fixed size.
        // Also this is a thread-safe implementation of a Queue
        // - which we need.
        this.eventQueue = new ArrayBlockingQueue<EventMessage>(EVENTQUEUE_SIZE);


        // Initialize the network background task for flushing the events.
        this.initializeBackgroundRunnable();

        // Initialize the default network connectivity status event tracking.
        this.initializeHooks();
    }

    /**
     * Initialize the default hooks which we will track
     * Network changes, and activity foreground/background.
     */
    private void initializeHooks() {

        // Register the activity state hooks
        ((Application) getApplicationContext()).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(Activity activity, Bundle bundle) {
            }

            @Override
            public void onActivityStarted(Activity activity) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
                Log.d(TAG, "onActivityResumed");
                try {
                    track("app", new JSONObject().put("ActivityState", "resumed"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onActivityPaused(Activity activity) {
                Log.d(TAG, "onActivityPaused");
                try {
                    track("app", new JSONObject().put("ActivityState", "paused"));
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onActivityStopped(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });

        // Register the network state hooks
        BroadcastReceiver chargerReceiver = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                ConnectivityManager cm =
                        (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
                boolean isConnected = activeNetwork != null &&
                        activeNetwork.isConnectedOrConnecting();

                if (lastNetworkState == null) {
                    lastNetworkState = isConnected;
                }

                // Don't report if already reported the same info.
                if (lastNetworkState != null && lastNetworkState == isConnected) {
                    return;
                }

                lastNetworkState = isConnected;
                Log.d(TAG, "onReceive: internet on? " + isConnected);

                try {
                    JSONObject json = new JSONObject();
                    json.put("NetworkStateOn", isConnected);
                    json.put("IPv4", Helpers.getLocalIpAddress());
                    track("network", json);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };

        // Register the network receiver
        registerReceiver(
                chargerReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
        );
    }


    /**
     * Adds a new event for the next batch of events sent to the server.
     * Note: event is used as the same meaning of action (only used because of specification).
     *
     * @param actionKey the identifier of the current event (network, location ...)
     * @param data      specific information for the current event.
     */
    public void track(String actionKey, JSONObject data) {
        EventMessage em = new EventMessage(actionKey, data);

        // Try to add a new event to the queue (producer)
        // If offer fails (eg the queue is full, we will remove elements until it works)
        while (!this.eventQueue.offer(em)) {
            Log.d(TAG, "track: removing old events from queue.");
            this.eventQueue.poll();
        }
    }

    /**
     * Initialize the background task worker and the main handler.
     * Will also start the background task immediately upon invocation.
     * Note: if I had more time I would read about more interfaces of bg tasks that
     * could achieve a more elegant solution.
     */
    private void initializeBackgroundRunnable() {
        // Initialize the handler & invoke
        this.mainHandlerThread = new HandlerThread("HandlerThread");
        this.mainHandlerThread.start();
        this.mainHandler = new Handler(this.mainHandlerThread.getLooper());

        // Start immediately
        this.mainHandler.postDelayed(new BackgroundRunnable(), 0);
    }

    /**
     * Helps user find out thier deviceUID.
     * Note: Specification given said that the deviceUID should be passed to the
     * constructor, so I am implementing this helper function, but I would get it
     * automatically in a real world case.
     *
     * @param context
     * @return String a unique device identifier used for constructing an EventTracker.
     */
    public static String getDeviceUID(Context context) {
        return Helpers.getUniqueDeviceId(context);
    }

    /**
     * Set the URL to send the tracked events to.
     * Must accept POST requests with JSON data.
     * @param apiEndpointUrl String
     */
    public static void setApiEndpointUrl(String apiEndpointUrl) {
        API_ENDPOINT_URL = apiEndpointUrl;
    }


    /**
     * The background runnable, running again each FLUSH_INTERVAL_SECONDS seconds.
     * Used for sending the events to the server in the background.
     */
    class BackgroundRunnable implements Runnable {

        private static final String TAG = "BackgroundRunnable";

        /**
         * Send the event to the defined api endpoint at API_ENDPOINT_URL
         * Note: If I would have more time then I would maybe add SSL Pinning.
         *
         * @param em EventMessage to send
         */
        private void sendEventHTTP(EventMessage em) {
            try {
                // 1. create the connection
                URL url = new URL(API_ENDPOINT_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

                // 2. convert the event message and other API data into JSON payload
                JSONObject jsonObject = Helpers.convertToHTTPPayload(apiKey, deviceUID, em);

                // 3. Attach the output stream and send data
                DataOutputStream dStream = new DataOutputStream(conn.getOutputStream());
                dStream.writeBytes(jsonObject.toString());
                dStream.flush();
                dStream.close();
                int responseCode = conn.getResponseCode();

                // 4. make POST request to the given URL
                conn.connect();
                Log.d(TAG, "sendEventHTTP: done..." + responseCode);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            EventMessage currentEventMessage = eventQueue.poll();
            Log.d(TAG, "First event: " + currentEventMessage);

            // Flush all the events sequentially (so the events will be ordered)
            // Note: we could add order metadata so we could send it asynchronously.
            while (currentEventMessage != null) {
                Log.d(TAG, "Current event: " + currentEventMessage);
                sendEventHTTP(currentEventMessage);
                currentEventMessage = eventQueue.poll();
            }

            // Queue this job again in FLUSH_INTERVAL_MS (10 seconds)
            mainHandler.postDelayed(this, FLUSH_INTERVAL_MS);
        }
    }
}
