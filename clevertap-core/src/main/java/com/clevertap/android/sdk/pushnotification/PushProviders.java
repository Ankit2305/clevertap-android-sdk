package com.clevertap.android.sdk.pushnotification;

import static android.content.Context.JOB_SCHEDULER_SERVICE;
import static android.content.Context.NOTIFICATION_SERVICE;
import static com.clevertap.android.sdk.BuildConfig.VERSION_CODE;
import static com.clevertap.android.sdk.pushnotification.PushNotificationUtil.getPushTypes;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.core.app.NotificationCompat;
import com.clevertap.android.sdk.AnalyticsManager;
import com.clevertap.android.sdk.BaseDatabaseManager;
import com.clevertap.android.sdk.CTExecutors;
import com.clevertap.android.sdk.CleverTapAPI.DevicePushTokenRefreshListener;
import com.clevertap.android.sdk.CleverTapInstanceConfig;
import com.clevertap.android.sdk.Constants;
import com.clevertap.android.sdk.ControllerManager;
import com.clevertap.android.sdk.DBAdapter;
import com.clevertap.android.sdk.DeviceInfo;
import com.clevertap.android.sdk.Logger;
import com.clevertap.android.sdk.ManifestInfo;
import com.clevertap.android.sdk.PostAsyncSafelyHandler;
import com.clevertap.android.sdk.StorageHelper;
import com.clevertap.android.sdk.Utils;
import com.clevertap.android.sdk.ValidationResult;
import com.clevertap.android.sdk.ValidationResultFactory;
import com.clevertap.android.sdk.ValidationResultStack;
import com.clevertap.android.sdk.pushnotification.PushConstants.PushType;
import com.clevertap.android.sdk.pushnotification.amp.CTBackgroundIntentService;
import com.clevertap.android.sdk.pushnotification.amp.CTBackgroundJobService;
import java.lang.reflect.Constructor;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Single point of contact to load & support all types of Notification messaging services viz. FCM, XPS, HMS etc.
 */

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class PushProviders {

    private final ArrayList<PushConstants.PushType> allEnabledPushTypes = new ArrayList<>();

    private final ArrayList<CTPushProvider> availableCTPushProviders = new ArrayList<>();

    private final ArrayList<PushConstants.PushType> customEnabledPushTypes = new ArrayList<>();

    private final AnalyticsManager mAnalyticsManager;

    private final BaseDatabaseManager mBaseDatabaseManager;

    private final CleverTapInstanceConfig mConfig;

    private final Context mContext;

    private final PostAsyncSafelyHandler mPostAsyncSafelyHandler;

    private final ValidationResultStack mValidationResultStack;

    private final Object tokenLock = new Object();

    private DevicePushTokenRefreshListener tokenRefreshListener;

    /**
     * Factory method to load push providers.
     *
     * @return A PushProviders class with the loaded providers.
     */
    @NonNull
    public static PushProviders load(Context context,
            CleverTapInstanceConfig config,
            BaseDatabaseManager baseDatabaseManager,
            PostAsyncSafelyHandler postAsyncSafelyHandler,
            ValidationResultStack validationResultStack,
            AnalyticsManager analyticsManager, ControllerManager controllerManager) {
        PushProviders providers = new PushProviders(context, config, baseDatabaseManager,
                postAsyncSafelyHandler, validationResultStack, analyticsManager);
        providers.init();
        controllerManager.setPushProviders(providers);
        return providers;
    }

    private PushProviders(
            Context context,
            CleverTapInstanceConfig config,
            BaseDatabaseManager baseDatabaseManager,
            PostAsyncSafelyHandler postAsyncSafelyHandler,
            ValidationResultStack validationResultStack,
            AnalyticsManager analyticsManager) {
        this.mContext = context;
        this.mConfig = config;
        mBaseDatabaseManager = baseDatabaseManager;
        mPostAsyncSafelyHandler = postAsyncSafelyHandler;
        mValidationResultStack = validationResultStack;
        mAnalyticsManager = analyticsManager;
        initPushAmp();
    }

    /**
     * Launches an asynchronous task to download the notification icon from CleverTap,
     * and create the Android notification.
     * <p/>
     * If your app is using CleverTap SDK's built in FCM message handling,
     * this method does not need to be called explicitly.
     * <p/>
     * Use this method when implementing your own FCM handling mechanism. Refer to the
     * SDK documentation for usage scenarios and examples.
     *
     * @param context        A reference to an Android context
     * @param extras         The {@link Bundle} object received by the broadcast receiver
     * @param notificationId A custom id to build a notification
     */
    public void _createNotification(final Context context, final Bundle extras, final int notificationId) {
        if (extras == null || extras.get(Constants.NOTIFICATION_TAG) == null) {
            return;
        }

        if (mConfig.isAnalyticsOnly()) {
            mConfig.getLogger()
                    .debug(mConfig.getAccountId(), "Instance is set for Analytics only, cannot create notification");
            return;
        }

        try {
            mPostAsyncSafelyHandler
                    .postAsyncSafely("CleverTapAPI#_createNotification", new Runnable() {
                        @Override
                        public void run() {
                            try {
                                mConfig.getLogger()
                                        .debug(mConfig.getAccountId(), "Handling notification: " + extras.toString());
                                if (extras.getString(Constants.WZRK_PUSH_ID) != null) {
                                    if (mBaseDatabaseManager.loadDBAdapter(context)
                                            .doesPushNotificationIdExist(extras.getString(Constants.WZRK_PUSH_ID))) {
                                        mConfig.getLogger().debug(mConfig.getAccountId(),
                                                "Push Notification already rendered, not showing again");
                                        return;
                                    }
                                }
                                String notifMessage = extras.getString(Constants.NOTIF_MSG);
                                notifMessage = (notifMessage != null) ? notifMessage : "";
                                if (notifMessage.isEmpty()) {
                                    //silent notification
                                    mConfig.getLogger()
                                            .verbose(mConfig.getAccountId(),
                                                    "Push notification message is empty, not rendering");
                                    mBaseDatabaseManager.loadDBAdapter(context)
                                            .storeUninstallTimestamp();
                                    String pingFreq = extras.getString("pf", "");
                                    if (!TextUtils.isEmpty(pingFreq)) {
                                        updatePingFrequencyIfNeeded(context, Integer.parseInt(pingFreq));
                                    }
                                    return;
                                }
                                String notifTitle = extras.getString(Constants.NOTIF_TITLE, "");
                                notifTitle = notifTitle.isEmpty() ? context.getApplicationInfo().name : notifTitle;
                                triggerNotification(context, extras, notifMessage, notifTitle, notificationId);
                            } catch (Throwable t) {
                                // Occurs if the notification image was null
                                // Let's return, as we couldn't get a handle on the app's icon
                                // Some devices throw a PackageManager* exception too
                                mConfig.getLogger()
                                        .debug(mConfig.getAccountId(), "Couldn't render notification: ", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            mConfig.getLogger().debug(mConfig.getAccountId(), "Failed to process push notification", t);
        }
    }

    /**
     * Saves token for a push type into shared pref
     *
     * @param token    - Messaging token
     * @param pushType - Pushtype, Ref{@link PushConstants.PushType}
     */
    public void cacheToken(final String token, final PushConstants.PushType pushType) {
        if (TextUtils.isEmpty(token) || pushType == null) {
            return;
        }

        try {
            CTExecutors.getInstance().diskIO().execute(new Runnable() {
                @Override
                public void run() {
                    if (alreadyHaveToken(token, pushType)) {
                        return;
                    }
                    @PushConstants.RegKeyType String key = pushType.getTokenPrefKey();
                    if (TextUtils.isEmpty(key)) {
                        return;
                    }
                    StorageHelper
                            .putStringImmediate(mContext, StorageHelper.storageKeyWithSuffix(mConfig, key), token);
                    mConfig.log(PushConstants.LOG_TAG, pushType + "Cached New Token successfully " + token);
                }
            });

        } catch (Throwable t) {
            mConfig.log(PushConstants.LOG_TAG, pushType + "Unable to cache token " + token, t);
        }
    }

    @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
    public void doTokenRefresh(String token, PushType pushType) {
        if (TextUtils.isEmpty(token) || pushType == null) {
            return;
        }
        switch (pushType) {
            case FCM:
                handleToken(token, PushType.FCM, true);
                break;
            case XPS:
                handleToken(token, PushType.XPS, true);
                break;
            case HPS:
                handleToken(token, PushType.HPS, true);
                break;
            case BPS:
                handleToken(token, PushType.BPS, true);
                break;
            case ADM:
                handleToken(token, PushType.ADM, true);
                break;
        }
    }

    /**
     * push the device token outside of the normal course
     */
    @RestrictTo(Scope.LIBRARY)
    public void forcePushDeviceToken(final boolean register) {

        for (PushType pushType : getAvailablePushTypes()) {
            pushDeviceTokenEvent(null, register, pushType);
        }
    }

    /**
     * @return list of all available push types, contains ( Clevertap's plugin + Custom supported Push Types)
     */
    @NonNull
    public ArrayList<PushConstants.PushType> getAvailablePushTypes() {
        ArrayList<PushConstants.PushType> pushTypes = new ArrayList<>();
        for (CTPushProvider pushProvider : availableCTPushProviders) {
            pushTypes.add(pushProvider.getPushType());
        }
        return pushTypes;
    }

    /**
     * @param pushType - Pushtype {@link PushConstants.PushType}
     * @return Messaging token for a particular push type
     */
    public String getCachedToken(PushConstants.PushType pushType) {
        if (pushType != null) {
            @PushConstants.RegKeyType String key = pushType.getTokenPrefKey();
            if (!TextUtils.isEmpty(key)) {
                String cachedToken = StorageHelper.getStringFromPrefs(mContext, mConfig, key, null);
                mConfig.log(PushConstants.LOG_TAG, pushType + "getting Cached Token - " + cachedToken);
                return cachedToken;
            }
        }
        if (pushType != null) {
            mConfig.log(PushConstants.LOG_TAG, pushType + " Unable to find cached Token for type ");
        }
        return null;
    }

    public DevicePushTokenRefreshListener getDevicePushTokenRefreshListener() {
        return tokenRefreshListener;
    }

    public void setDevicePushTokenRefreshListener(final DevicePushTokenRefreshListener tokenRefreshListener) {
        this.tokenRefreshListener = tokenRefreshListener;
    }

    /**
     * Direct Method to send tokens to Clevertap's server
     * Call this method when Clients are handling the Messaging services on their own
     *
     * @param token    - Messaging token
     * @param pushType - Pushtype, Ref:{@link PushConstants.PushType}
     * @param register - true if we want to register the token to CT server
     *                 false if we want to unregister the token from CT server
     */
    public void handleToken(String token, PushConstants.PushType pushType, boolean register) {
        if (register) {
            registerToken(token, pushType);
        } else {
            unregisterToken(token, pushType);
        }
    }

    /**
     * @return true if we are able to reach the device via any of the messaging service
     */
    public boolean isNotificationSupported() {
        for (PushConstants.PushType pushType : getAvailablePushTypes()) {
            if (getCachedToken(pushType) != null) {
                return true;
            }
        }
        return false;
    }

    public void onNewToken(String freshToken, PushConstants.PushType pushType) {
        if (!TextUtils.isEmpty(freshToken)) {
            doTokenRefresh(freshToken, pushType);
            deviceTokenDidRefresh(freshToken, pushType);
        }
    }

    //Push
    public void onTokenRefresh() {
        refreshAllTokens();
    }

    /**
     * Stores silent push notification in DB for smooth working of Push Amplification
     * Background Job Service and also stores wzrk_pid to the DB to avoid duplication of Push
     * Notifications from Push Amplification.
     *
     * @param extras - Bundle
     */
    public void processCustomPushNotification(final Bundle extras) {
        mPostAsyncSafelyHandler.postAsyncSafely("customHandlePushAmplification", new Runnable() {
            @Override
            public void run() {
                String notifMessage = extras.getString(Constants.NOTIF_MSG);
                notifMessage = (notifMessage != null) ? notifMessage : "";
                if (notifMessage.isEmpty()) {
                    //silent notification
                    mConfig.getLogger()
                            .verbose(mConfig.getAccountId(), "Push notification message is empty, not rendering");
                    mBaseDatabaseManager.loadDBAdapter(mContext).storeUninstallTimestamp();
                    String pingFreq = extras.getString("pf", "");
                    if (!TextUtils.isEmpty(pingFreq)) {
                        updatePingFrequencyIfNeeded(mContext, Integer.parseInt(pingFreq));
                    }
                } else {
                    String wzrk_pid = extras.getString(Constants.WZRK_PUSH_ID);
                    String ttl = extras.getString(Constants.WZRK_TIME_TO_LIVE,
                            (System.currentTimeMillis() + Constants.DEFAULT_PUSH_TTL) / 1000 + "");
                    long wzrk_ttl = Long.parseLong(ttl);
                    DBAdapter dbAdapter = mBaseDatabaseManager.loadDBAdapter(mContext);
                    mConfig.getLogger().verbose("Storing Push Notification..." + wzrk_pid + " - with ttl - " + ttl);
                    dbAdapter.storePushNotificationId(wzrk_pid, wzrk_ttl);
                }
            }
        });
    }

    public void runInstanceJobWork(final Context context, final JobParameters parameters) {
        mPostAsyncSafelyHandler.postAsyncSafely("runningJobService", new Runnable() {
            @Override
            public void run() {
                if (isNotificationSupported()) {
                    Logger.v(mConfig.getAccountId(), "Token is not present, not running the Job");
                    return;
                }

                Calendar now = Calendar.getInstance();

                int hour = now.get(Calendar.HOUR_OF_DAY); // Get hour in 24 hour format
                int minute = now.get(Calendar.MINUTE);

                Date currentTime = parseTimeToDate(hour + ":" + minute);
                Date startTime = parseTimeToDate(Constants.DND_START);
                Date endTime = parseTimeToDate(Constants.DND_STOP);

                if (isTimeBetweenDNDTime(startTime, endTime, currentTime)) {
                    Logger.v(mConfig.getAccountId(), "Job Service won't run in default DND hours");
                    return;
                }

                long lastTS = mBaseDatabaseManager.loadDBAdapter(context).getLastUninstallTimestamp();

                if (lastTS == 0 || lastTS > System.currentTimeMillis() - 24 * 60 * 60 * 1000) {
                    try {
                        JSONObject eventObject = new JSONObject();
                        eventObject.put("bk", 1);
                        mAnalyticsManager.sendPingEvent(eventObject);

                        if (parameters == null) {
                            int pingFrequency = getPingFrequency(context);
                            AlarmManager alarmManager = (AlarmManager) context
                                    .getSystemService(Context.ALARM_SERVICE);
                            Intent cancelIntent = new Intent(CTBackgroundIntentService.MAIN_ACTION);
                            cancelIntent.setPackage(context.getPackageName());
                            PendingIntent alarmPendingIntent = PendingIntent
                                    .getService(context, mConfig.getAccountId().hashCode(), cancelIntent,
                                            PendingIntent.FLAG_UPDATE_CURRENT);
                            if (alarmManager != null) {
                                alarmManager.cancel(alarmPendingIntent);
                            }
                            Intent alarmIntent = new Intent(CTBackgroundIntentService.MAIN_ACTION);
                            alarmIntent.setPackage(context.getPackageName());
                            PendingIntent alarmServicePendingIntent = PendingIntent
                                    .getService(context, mConfig.getAccountId().hashCode(), alarmIntent,
                                            PendingIntent.FLAG_UPDATE_CURRENT);
                            if (alarmManager != null) {
                                if (pingFrequency != -1) {
                                    alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                                            SystemClock.elapsedRealtime() + (pingFrequency
                                                    * Constants.ONE_MIN_IN_MILLIS),
                                            Constants.ONE_MIN_IN_MILLIS * pingFrequency, alarmServicePendingIntent);
                                }
                            }
                        }
                    } catch (JSONException e) {
                        Logger.v("Unable to raise background Ping event");
                    }

                }
            }
        });
    }

    /**
     * Unregister the token for a push type from Clevertap's server.
     * Devices with unregistered token wont be reachable.
     *
     * @param token    - Messaging token
     * @param pushType - pushtype Ref:{@link PushConstants.PushType}
     */
    public void unregisterToken(String token, PushConstants.PushType pushType) {
        pushDeviceTokenEvent(token, false, pushType);
    }

    /**
     * updates the ping frequency if there is a change & reschedules existing ping tasks.
     */
    public void updatePingFrequencyIfNeeded(final Context context, int frequency) {
        mConfig.getLogger().verbose("Ping frequency received - " + frequency);
        mConfig.getLogger().verbose("Stored Ping Frequency - " + getPingFrequency(context));
        if (frequency != getPingFrequency(context)) {
            setPingFrequency(context, frequency);
            if (mConfig.isBackgroundSync() && !mConfig.isAnalyticsOnly()) {
                mPostAsyncSafelyHandler
                        .postAsyncSafely("createOrResetJobScheduler", new Runnable() {
                            @Override
                            public void run() {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    mConfig.getLogger().verbose("Creating job");
                                    createOrResetJobScheduler(context);
                                } else {
                                    mConfig.getLogger().verbose("Resetting alarm");
                                    resetAlarmScheduler(context);
                                }
                            }
                        });
            }
        }
    }

    private boolean alreadyHaveToken(String newToken, PushConstants.PushType pushType) {
        boolean alreadyAvailable = !TextUtils.isEmpty(newToken) && pushType != null && newToken
                .equalsIgnoreCase(getCachedToken(pushType));
        if (pushType != null) {
            mConfig.log(PushConstants.LOG_TAG, pushType + "Token Already available value: " + alreadyAvailable);
        }
        return alreadyAvailable;
    }

    private void createAlarmScheduler(Context context) {
        int pingFrequency = getPingFrequency(context);
        if (pingFrequency > 0) {
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(CTBackgroundIntentService.MAIN_ACTION);
            intent.setPackage(context.getPackageName());
            PendingIntent alarmPendingIntent = PendingIntent
                    .getService(context, mConfig.getAccountId().hashCode(), intent,
                            PendingIntent.FLAG_UPDATE_CURRENT);
            if (alarmManager != null) {
                alarmManager.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime(),
                        Constants.ONE_MIN_IN_MILLIS * pingFrequency, alarmPendingIntent);
            }
        }
    }

    @SuppressLint("MissingPermission")
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void createOrResetJobScheduler(Context context) {

        int existingJobId = StorageHelper.getInt(context, Constants.PF_JOB_ID, -1);
        JobScheduler jobScheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);

        //Disable push amp for devices below Api 26
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (existingJobId >= 0) {//cancel already running job
                jobScheduler.cancel(existingJobId);
                StorageHelper.putInt(context, Constants.PF_JOB_ID, -1);
            }

            mConfig.getLogger()
                    .debug(mConfig.getAccountId(), "Push Amplification feature is not supported below Oreo");
            return;
        }

        if (jobScheduler == null) {
            return;
        }
        int pingFrequency = getPingFrequency(context);

        if (existingJobId < 0 && pingFrequency < 0) {
            return; //no running job and nothing to create
        }

        if (pingFrequency < 0) { //running job but hard cancel
            jobScheduler.cancel(existingJobId);
            StorageHelper.putInt(context, Constants.PF_JOB_ID, -1);
            return;
        }

        ComponentName componentName = new ComponentName(context, CTBackgroundJobService.class);
        boolean needsCreate = (existingJobId < 0 && pingFrequency > 0);

        //running job, no hard cancel so check for diff in ping frequency and recreate if needed
        JobInfo existingJobInfo = getJobInfo(existingJobId, jobScheduler);
        if (existingJobInfo != null
                && existingJobInfo.getIntervalMillis() != pingFrequency * Constants.ONE_MIN_IN_MILLIS) {
            jobScheduler.cancel(existingJobId);
            StorageHelper.putInt(context, Constants.PF_JOB_ID, -1);
            needsCreate = true;
        }

        if (needsCreate) {
            int jobid = mConfig.getAccountId().hashCode();
            JobInfo.Builder builder = new JobInfo.Builder(jobid, componentName);
            builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
            builder.setRequiresCharging(false);

            builder.setPeriodic(pingFrequency * Constants.ONE_MIN_IN_MILLIS, 5 * Constants.ONE_MIN_IN_MILLIS);
            builder.setRequiresBatteryNotLow(true);

            if (Utils.hasPermission(context, "android.permission.RECEIVE_BOOT_COMPLETED")) {
                builder.setPersisted(true);
            }

            JobInfo jobInfo = builder.build();
            int resultCode = jobScheduler.schedule(jobInfo);
            if (resultCode == JobScheduler.RESULT_SUCCESS) {
                Logger.d(mConfig.getAccountId(), "Job scheduled - " + jobid);
                StorageHelper.putInt(context, Constants.PF_JOB_ID, jobid);
            } else {
                Logger.d(mConfig.getAccountId(), "Job not scheduled - " + jobid);
            }
        }
    }

    /**
     * Creates the list of push providers.
     *
     * @return The list of push providers.
     */
    @NonNull
    private List<CTPushProvider> createProviders() {
        List<CTPushProvider> providers = new ArrayList<>();

        for (PushConstants.PushType pushType : allEnabledPushTypes) {
            String className = pushType.getCtProviderClassName();
            CTPushProvider pushProvider = null;
            try {
                Class<?> providerClass = Class.forName(className);
                Constructor<?> constructor = providerClass.getConstructor(CTPushProviderListener.class);
                pushProvider = (CTPushProvider) constructor.newInstance(this);
                mConfig.log(PushConstants.LOG_TAG, "Found provider:" + className);
            } catch (InstantiationException e) {
                mConfig.log(PushConstants.LOG_TAG, "Unable to create provider InstantiationException" + className);
            } catch (IllegalAccessException e) {
                mConfig.log(PushConstants.LOG_TAG, "Unable to create provider IllegalAccessException" + className);
            } catch (ClassNotFoundException e) {
                mConfig.log(PushConstants.LOG_TAG, "Unable to create provider ClassNotFoundException" + className);
            } catch (Exception e) {
                mConfig.log(PushConstants.LOG_TAG,
                        "Unable to create provider " + className + " Exception:" + e.getClass().getName());
            }

            if (pushProvider == null) {
                continue;
            }

            providers.add(pushProvider);
        }

        return providers;
    }

    //Push
    @SuppressWarnings("SameParameterValue")
    private void deviceTokenDidRefresh(String token, PushType type) {
        if (tokenRefreshListener != null) {
            mConfig.getLogger().debug(mConfig.getAccountId(), "Notifying devicePushTokenDidRefresh: " + token);
            tokenRefreshListener.devicePushTokenDidRefresh(token, type);
        }
    }

    private void findCTPushProviders(List<CTPushProvider> providers) {
        if (providers.isEmpty()) {
            mConfig.log(PushConstants.LOG_TAG,
                    "No push providers found!. Make sure to install at least one push provider");
            return;
        }

        for (CTPushProvider provider : providers) {
            if (!isValid(provider)) {
                mConfig.log(PushConstants.LOG_TAG, "Invalid Provider: " + provider.getClass());
                continue;
            }

            if (!provider.isSupported()) {
                mConfig.log(PushConstants.LOG_TAG, "Unsupported Provider: " + provider.getClass());
                continue;
            }

            if (provider.isAvailable()) {
                mConfig.log(PushConstants.LOG_TAG, "Available Provider: " + provider.getClass());
                availableCTPushProviders.add(provider);
            } else {
                mConfig.log(PushConstants.LOG_TAG, "Unavailable Provider: " + provider.getClass());
            }
        }
    }

    private void findCustomEnabledPushTypes() {
        customEnabledPushTypes.addAll(allEnabledPushTypes);
        for (final CTPushProvider pushProvider : availableCTPushProviders) {
            customEnabledPushTypes.remove(pushProvider.getPushType());
        }
    }

    //Session

    private void findEnabledPushTypes() {
        for (PushConstants.PushType pushType : getPushTypes(mConfig.getAllowedPushTypes())) {
            String className = pushType.getMessagingSDKClassName();
            try {
                Class.forName(className);
                allEnabledPushTypes.add(pushType);
                mConfig.log(PushConstants.LOG_TAG, "SDK Class Available :" + className);
            } catch (Exception e) {
                mConfig.log(PushConstants.LOG_TAG,
                        "SDK class Not available " + className + " Exception:" + e.getClass().getName());
            }
        }
    }

    private int getPingFrequency(Context context) {
        return StorageHelper.getInt(context, Constants.PING_FREQUENCY,
                Constants.PING_FREQUENCY_VALUE); //intentional global key because only one Job is running
    }

    /**
     * Loads all the plugins that are currently supported by the device.
     */
    private void init() {

        findEnabledPushTypes();

        List<CTPushProvider> providers = createProviders();

        findCTPushProviders(providers);

        findCustomEnabledPushTypes();
    }

    private void initPushAmp() {
        if (mConfig.isBackgroundSync() && !mConfig
                .isAnalyticsOnly()) {
            mPostAsyncSafelyHandler.postAsyncSafely("createOrResetJobScheduler", new Runnable() {
                @Override
                public void run() {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        createOrResetJobScheduler(mContext);
                    } else {
                        createAlarmScheduler(mContext);
                    }
                }
            });
        }
    }

    @SuppressWarnings("SameParameterValue")
    private boolean isServiceAvailable(Context context, Class clazz) {
        if (clazz == null) {
            return false;
        }

        PackageManager pm = context.getPackageManager();
        String packageName = context.getPackageName();

        PackageInfo packageInfo;
        try {
            packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SERVICES);
            ServiceInfo[] services = packageInfo.services;
            for (ServiceInfo serviceInfo : services) {
                if (serviceInfo.name.equals(clazz.getName())) {
                    Logger.v("Service " + serviceInfo.name + " found");
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            Logger.d("Intent Service name not found exception - " + e.getLocalizedMessage());
        }
        return false;
    }

    private boolean isTimeBetweenDNDTime(Date startTime, Date stopTime, Date currentTime) {
        //Start Time
        Calendar startTimeCalendar = Calendar.getInstance();
        startTimeCalendar.setTime(startTime);
        //Current Time
        Calendar currentTimeCalendar = Calendar.getInstance();
        currentTimeCalendar.setTime(currentTime);
        //Stop Time
        Calendar stopTimeCalendar = Calendar.getInstance();
        stopTimeCalendar.setTime(stopTime);

        if (stopTime.compareTo(startTime) < 0) {
            if (currentTimeCalendar.compareTo(stopTimeCalendar) < 0) {
                currentTimeCalendar.add(Calendar.DATE, 1);
            }
            stopTimeCalendar.add(Calendar.DATE, 1);
        }
        return currentTimeCalendar.compareTo(startTimeCalendar) >= 0
                && currentTimeCalendar.compareTo(stopTimeCalendar) < 0;
    }

    private boolean isValid(CTPushProvider provider) {

        if (VERSION_CODE < provider.minSDKSupportVersionCode()) {
            mConfig.log(PushConstants.LOG_TAG,
                    "Provider: %s version %s does not match the SDK version %s. Make sure all Airship dependencies are the same version.");
            return false;
        }
        switch (provider.getPushType()) {
            case FCM:
            case HPS:
            case XPS:
            case BPS:
                if (provider.getPlatform() != PushConstants.ANDROID_PLATFORM) {
                    mConfig.log(PushConstants.LOG_TAG, "Invalid Provider: " + provider.getClass() +
                            " delivery is only available for Android platforms." + provider.getPushType());
                    return false;
                }
                break;
            case ADM:
                if (provider.getPlatform() != PushConstants.AMAZON_PLATFORM) {
                    mConfig.log(PushConstants.LOG_TAG, "Invalid Provider: " +
                            provider.getClass() +
                            " ADM delivery is only available for Amazon platforms." + provider.getPushType());
                    return false;
                }
                break;
        }

        return true;
    }

    private Date parseTimeToDate(String time) {

        final String inputFormat = "HH:mm";
        SimpleDateFormat inputParser = new SimpleDateFormat(inputFormat, Locale.US);
        try {
            return inputParser.parse(time);
        } catch (java.text.ParseException e) {
            return new Date(0);
        }
    }

    private void pushDeviceTokenEvent(String token, boolean register, PushType pushType) {
        if (pushType == null) {
            return;
        }
        token = !TextUtils.isEmpty(token) ? token : getCachedToken(pushType);
        if (TextUtils.isEmpty(token)) {
            return;
        }
        synchronized (tokenLock) {
            JSONObject event = new JSONObject();
            JSONObject data = new JSONObject();
            String action = register ? "register" : "unregister";
            try {
                data.put("action", action);
                data.put("id", token);
                data.put("type", pushType.getType());
                event.put("data", data);
                mConfig.getLogger().verbose(mConfig.getAccountId(), pushType + action + " device token " + token);
                mAnalyticsManager.sendDataEvent(event);
            } catch (Throwable t) {
                // we won't get here
                mConfig.getLogger().verbose(mConfig.getAccountId(), pushType + action + " device token failed", t);
            }
        }
    }

    /**
     * Fetches latest tokens from various providers and send to Clevertap's server
     */
    private void refreshAllTokens() {
        CTExecutors.getInstance().diskIO().execute(new Runnable() {
            @Override
            public void run() {
                // refresh tokens of Push Providers
                refreshCTProviderTokens();

                // refresh tokens of custom Providers
                refreshCustomProviderTokens();
            }
        });
    }

    private void refreshCTProviderTokens() {
        for (final CTPushProvider pushProvider : availableCTPushProviders) {
            try {
                pushProvider.requestToken();
            } catch (Throwable t) {
                //no-op
                mConfig.log(PushConstants.LOG_TAG, "Token Refresh error " + pushProvider, t);
            }
        }
    }

    private void refreshCustomProviderTokens() {
        for (PushConstants.PushType pushType : customEnabledPushTypes) {
            try {
                pushDeviceTokenEvent(getCachedToken(pushType), true, pushType);
            } catch (Throwable t) {
                mConfig.log(PushConstants.LOG_TAG, "Token Refresh error " + pushType, t);
            }
        }
    }

    private void registerToken(String token, PushConstants.PushType pushType) {
        pushDeviceTokenEvent(token, true, pushType);
        cacheToken(token, pushType);
    }

    private void resetAlarmScheduler(Context context) {
        if (getPingFrequency(context) <= 0) {
            stopAlarmScheduler(context);
        } else {
            stopAlarmScheduler(context);
            createAlarmScheduler(context);
        }
    }

    private void setPingFrequency(Context context, int pingFrequency) {
        StorageHelper.putInt(context, Constants.PING_FREQUENCY, pingFrequency);
    }

    private void stopAlarmScheduler(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent cancelIntent = new Intent(CTBackgroundIntentService.MAIN_ACTION);
        cancelIntent.setPackage(context.getPackageName());
        PendingIntent alarmPendingIntent = PendingIntent
                .getService(context, mConfig.getAccountId().hashCode(), cancelIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
        if (alarmManager != null && alarmPendingIntent != null) {
            alarmManager.cancel(alarmPendingIntent);
        }
    }

    private void triggerNotification(Context context, Bundle extras, String notifMessage, String notifTitle,
            int notificationId) {
        NotificationManager notificationManager =
                (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            String notificationManagerError = "Unable to render notification, Notification Manager is null.";
            mConfig.getLogger().debug(mConfig.getAccountId(), notificationManagerError);
            return;
        }

        String channelId = extras.getString(Constants.WZRK_CHANNEL_ID, "");
        boolean requiresChannelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int messageCode = -1;
            String value = "";

            if (channelId.isEmpty()) {
                messageCode = Constants.CHANNEL_ID_MISSING_IN_PAYLOAD;
                value = extras.toString();
            } else if (notificationManager.getNotificationChannel(channelId) == null) {
                messageCode = Constants.CHANNEL_ID_NOT_REGISTERED;
                value = channelId;
            }
            if (messageCode != -1) {
                ValidationResult channelIdError = ValidationResultFactory.create(512, messageCode, value);
                mConfig.getLogger().debug(mConfig.getAccountId(), channelIdError.getErrorDesc());
                mValidationResultStack.pushValidationResult(channelIdError);
                return;
            }
        }

        String icoPath = extras.getString(Constants.NOTIF_ICON);
        Intent launchIntent = new Intent(context, CTPushNotificationReceiver.class);

        PendingIntent pIntent;

        // Take all the properties from the notif and add it to the intent
        launchIntent.putExtras(extras);
        launchIntent.removeExtra(Constants.WZRK_ACTIONS);
        pIntent = PendingIntent.getBroadcast(context, (int) System.currentTimeMillis(),
                launchIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Style style;
        String bigPictureUrl = extras.getString(Constants.WZRK_BIG_PICTURE);
        if (bigPictureUrl != null && bigPictureUrl.startsWith("http")) {
            try {
                Bitmap bpMap = Utils.getNotificationBitmap(bigPictureUrl, false, context);

                if (bpMap == null) {
                    throw new Exception("Failed to fetch big picture!");
                }

                if (extras.containsKey(Constants.WZRK_MSG_SUMMARY)) {
                    String summaryText = extras.getString(Constants.WZRK_MSG_SUMMARY);
                    style = new NotificationCompat.BigPictureStyle()
                            .setSummaryText(summaryText)
                            .bigPicture(bpMap);
                } else {
                    style = new NotificationCompat.BigPictureStyle()
                            .setSummaryText(notifMessage)
                            .bigPicture(bpMap);
                }
            } catch (Throwable t) {
                style = new NotificationCompat.BigTextStyle()
                        .bigText(notifMessage);
                mConfig.getLogger()
                        .verbose(mConfig.getAccountId(),
                                "Falling back to big text notification, couldn't fetch big picture",
                                t);
            }
        } else {
            style = new NotificationCompat.BigTextStyle()
                    .bigText(notifMessage);
        }

        int smallIcon;
        try {
            String x = ManifestInfo.getInstance(context).getNotificationIcon();
            if (x == null) {
                throw new IllegalArgumentException();
            }
            smallIcon = context.getResources().getIdentifier(x, "drawable", context.getPackageName());
            if (smallIcon == 0) {
                throw new IllegalArgumentException();
            }
        } catch (Throwable t) {
            smallIcon = DeviceInfo.getAppIconAsIntId(context);
        }

        int priorityInt = NotificationCompat.PRIORITY_DEFAULT;
        String priority = extras.getString(Constants.NOTIF_PRIORITY);
        if (priority != null) {
            if (priority.equals(Constants.PRIORITY_HIGH)) {
                priorityInt = NotificationCompat.PRIORITY_HIGH;
            }
            if (priority.equals(Constants.PRIORITY_MAX)) {
                priorityInt = NotificationCompat.PRIORITY_MAX;
            }
        }

        // if we have no user set notificationID then try collapse key
        if (notificationId == Constants.EMPTY_NOTIFICATION_ID) {
            try {
                Object collapse_key = extras.get(Constants.WZRK_COLLAPSE);
                if (collapse_key != null) {
                    if (collapse_key instanceof Number) {
                        notificationId = ((Number) collapse_key).intValue();
                    } else if (collapse_key instanceof String) {
                        try {
                            notificationId = Integer.parseInt(collapse_key.toString());
                            mConfig.getLogger().debug(mConfig.getAccountId(),
                                    "Converting collapse_key: " + collapse_key + " to notificationId int: "
                                            + notificationId);
                        } catch (NumberFormatException e) {
                            notificationId = (collapse_key.toString().hashCode());
                            mConfig.getLogger().debug(mConfig.getAccountId(),
                                    "Converting collapse_key: " + collapse_key + " to notificationId int: "
                                            + notificationId);
                        }
                    }
                }
            } catch (NumberFormatException e) {
                // no-op
            }
        } else {
            mConfig.getLogger().debug(mConfig.getAccountId(), "Have user provided notificationId: " + notificationId
                    + " won't use collapse_key (if any) as basis for notificationId");
        }

        // if after trying collapse_key notification is still empty set to random int
        if (notificationId == Constants.EMPTY_NOTIFICATION_ID) {
            notificationId = (int) (Math.random() * 100);
            mConfig.getLogger().debug(mConfig.getAccountId(), "Setting random notificationId: " + notificationId);
        }

        NotificationCompat.Builder nb;
        if (requiresChannelId) {
            nb = new NotificationCompat.Builder(context, channelId);

            // choices here are Notification.BADGE_ICON_NONE = 0, Notification.BADGE_ICON_SMALL = 1, Notification.BADGE_ICON_LARGE = 2.  Default is  Notification.BADGE_ICON_LARGE
            String badgeIconParam = extras.getString(Constants.WZRK_BADGE_ICON, null);
            if (badgeIconParam != null) {
                try {
                    int badgeIconType = Integer.parseInt(badgeIconParam);
                    if (badgeIconType >= 0) {
                        nb.setBadgeIconType(badgeIconType);
                    }
                } catch (Throwable t) {
                    // no-op
                }
            }

            String badgeCountParam = extras.getString(Constants.WZRK_BADGE_COUNT, null);
            if (badgeCountParam != null) {
                try {
                    int badgeCount = Integer.parseInt(badgeCountParam);
                    if (badgeCount >= 0) {
                        nb.setNumber(badgeCount);
                    }
                } catch (Throwable t) {
                    // no-op
                }
            }
            if (extras.containsKey(Constants.WZRK_SUBTITLE)) {
                nb.setSubText(extras.getString(Constants.WZRK_SUBTITLE));
            }
        } else {
            // noinspection all
            nb = new NotificationCompat.Builder(context);
        }

        if (extras.containsKey(Constants.WZRK_COLOR)) {
            int color = Color.parseColor(extras.getString(Constants.WZRK_COLOR));
            nb.setColor(color);
            nb.setColorized(true);
        }

        nb.setContentTitle(notifTitle)
                .setContentText(notifMessage)
                .setContentIntent(pIntent)
                .setAutoCancel(true)
                .setStyle(style)
                .setPriority(priorityInt)
                .setSmallIcon(smallIcon);

        nb.setLargeIcon(Utils.getNotificationBitmap(icoPath, true, context));

        try {
            if (extras.containsKey(Constants.WZRK_SOUND)) {
                Uri soundUri = null;

                Object o = extras.get(Constants.WZRK_SOUND);

                if ((o instanceof Boolean && (Boolean) o)) {
                    soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                } else if (o instanceof String) {
                    String s = (String) o;
                    if (s.equals("true")) {
                        soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                    } else if (!s.isEmpty()) {
                        if (s.contains(".mp3") || s.contains(".ogg") || s.contains(".wav")) {
                            s = s.substring(0, (s.length() - 4));
                        }
                        soundUri = Uri
                                .parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + context.getPackageName()
                                        + "/raw/" + s);

                    }
                }

                if (soundUri != null) {
                    nb.setSound(soundUri);
                }
            }
        } catch (Throwable t) {
            mConfig.getLogger().debug(mConfig.getAccountId(), "Could not process sound parameter", t);
        }

        // add actions if any
        JSONArray actions = null;
        String actionsString = extras.getString(Constants.WZRK_ACTIONS);
        if (actionsString != null) {
            try {
                actions = new JSONArray(actionsString);
            } catch (Throwable t) {
                mConfig.getLogger()
                        .debug(mConfig.getAccountId(),
                                "error parsing notification actions: " + t.getLocalizedMessage());
            }
        }

        String intentServiceName = ManifestInfo.getInstance(context).getIntentServiceName();
        Class clazz = null;
        if (intentServiceName != null) {
            try {
                clazz = Class.forName(intentServiceName);
            } catch (ClassNotFoundException e) {
                try {
                    clazz = Class.forName("com.clevertap.android.sdk.pushnotification.CTNotificationIntentService");
                } catch (ClassNotFoundException ex) {
                    Logger.d("No Intent Service found");
                }
            }
        } else {
            try {
                clazz = Class.forName("com.clevertap.android.sdk.pushnotification.CTNotificationIntentService");
            } catch (ClassNotFoundException ex) {
                Logger.d("No Intent Service found");
            }
        }

        boolean isCTIntentServiceAvailable = isServiceAvailable(context, clazz);

        if (actions != null && actions.length() > 0) {
            for (int i = 0; i < actions.length(); i++) {
                try {
                    JSONObject action = actions.getJSONObject(i);
                    String label = action.optString("l");
                    String dl = action.optString("dl");
                    String ico = action.optString(Constants.NOTIF_ICON);
                    String id = action.optString("id");
                    boolean autoCancel = action.optBoolean("ac", true);
                    if (label.isEmpty() || id.isEmpty()) {
                        mConfig.getLogger().debug(mConfig.getAccountId(),
                                "not adding push notification action: action label or id missing");
                        continue;
                    }
                    int icon = 0;
                    if (!ico.isEmpty()) {
                        try {
                            icon = context.getResources().getIdentifier(ico, "drawable", context.getPackageName());
                        } catch (Throwable t) {
                            mConfig.getLogger().debug(mConfig.getAccountId(),
                                    "unable to add notification action icon: " + t.getLocalizedMessage());
                        }
                    }

                    boolean sendToCTIntentService = (autoCancel && isCTIntentServiceAvailable);

                    Intent actionLaunchIntent;
                    if (sendToCTIntentService) {
                        actionLaunchIntent = new Intent(CTNotificationIntentService.MAIN_ACTION);
                        actionLaunchIntent.setPackage(context.getPackageName());
                        actionLaunchIntent.putExtra("ct_type", CTNotificationIntentService.TYPE_BUTTON_CLICK);
                        if (!dl.isEmpty()) {
                            actionLaunchIntent.putExtra("dl", dl);
                        }
                    } else {
                        if (!dl.isEmpty()) {
                            actionLaunchIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(dl));
                        } else {
                            actionLaunchIntent = context.getPackageManager()
                                    .getLaunchIntentForPackage(context.getPackageName());
                        }
                    }

                    if (actionLaunchIntent != null) {
                        actionLaunchIntent.putExtras(extras);
                        actionLaunchIntent.removeExtra(Constants.WZRK_ACTIONS);
                        actionLaunchIntent.putExtra("actionId", id);
                        actionLaunchIntent.putExtra("autoCancel", autoCancel);
                        actionLaunchIntent.putExtra("wzrk_c2a", id);
                        actionLaunchIntent.putExtra("notificationId", notificationId);

                        actionLaunchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    }

                    PendingIntent actionIntent;
                    int requestCode = ((int) System.currentTimeMillis()) + i;
                    if (sendToCTIntentService) {
                        actionIntent = PendingIntent.getService(context, requestCode,
                                actionLaunchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                    } else {
                        actionIntent = PendingIntent.getActivity(context, requestCode,
                                actionLaunchIntent, PendingIntent.FLAG_UPDATE_CURRENT);
                    }
                    nb.addAction(icon, label, actionIntent);

                } catch (Throwable t) {
                    mConfig.getLogger()
                            .debug(mConfig.getAccountId(),
                                    "error adding notification action : " + t.getLocalizedMessage());
                }
            }
        }

        Notification n = nb.build();
        notificationManager.notify(notificationId, n);
        mConfig.getLogger().debug(mConfig.getAccountId(), "Rendered notification: " + n.toString());

        String ttl = extras.getString(Constants.WZRK_TIME_TO_LIVE,
                (System.currentTimeMillis() + Constants.DEFAULT_PUSH_TTL) / 1000 + "");
        long wzrk_ttl = Long.parseLong(ttl);
        String wzrk_pid = extras.getString(Constants.WZRK_PUSH_ID);
        DBAdapter dbAdapter = mBaseDatabaseManager.loadDBAdapter(context);
        mConfig.getLogger().verbose("Storing Push Notification..." + wzrk_pid + " - with ttl - " + ttl);
        dbAdapter.storePushNotificationId(wzrk_pid, wzrk_ttl);

        boolean notificationViewedEnabled = "true".equals(extras.getString(Constants.WZRK_RNV, ""));
        if (!notificationViewedEnabled) {
            ValidationResult notificationViewedError = ValidationResultFactory
                    .create(512, Constants.NOTIFICATION_VIEWED_DISABLED, extras.toString());
            mConfig.getLogger().debug(notificationViewedError.getErrorDesc());
            mValidationResultStack.pushValidationResult(notificationViewedError);
            return;
        }
        mAnalyticsManager.pushNotificationViewedEvent(extras);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private static JobInfo getJobInfo(int jobId, JobScheduler jobScheduler) {
        for (JobInfo jobInfo : jobScheduler.getAllPendingJobs()) {
            if (jobInfo.getId() == jobId) {
                return jobInfo;
            }
        }
        return null;
    }
}