package com.clevertap.android.sdk;

import static com.clevertap.android.sdk.Utils.runOnUiThread;

import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import com.clevertap.android.sdk.InAppNotificationActivity.InAppActivityListener;
import com.clevertap.android.sdk.displayunits.DisplayUnitListener;
import com.clevertap.android.sdk.displayunits.model.CleverTapDisplayUnit;
import com.clevertap.android.sdk.product_config.CTProductConfigListener;
import com.clevertap.android.sdk.pushnotification.CTPushNotificationListener;
import com.clevertap.android.sdk.pushnotification.amp.CTPushAmpListener;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

@RestrictTo(Scope.LIBRARY)
public class CallbackManager extends BaseCallbackManager {

    private InAppActivityListener mInAppActivityListener;

    private FailureFlushListener mFailureFlushListener;

    private WeakReference<DisplayUnitListener> displayUnitListenerWeakReference;

    private GeofenceCallback geofenceCallback;

    private WeakReference<InAppNotificationButtonListener> inAppNotificationButtonListener;

    private InAppNotificationListener inAppNotificationListener;

    private CTInboxListener inboxListener;

    private final CleverTapInstanceConfig mConfig;

    private final DeviceInfo mDeviceInfo;

    private WeakReference<CTFeatureFlagsListener> mFeatureFlagListenerWeakReference;

    private WeakReference<CTProductConfigListener> productConfigListener;

    private CTPushAmpListener pushAmpListener = null;

    private CTPushNotificationListener pushNotificationListener = null;

    private SyncListener syncListener = null;

    CallbackManager(CleverTapInstanceConfig config, DeviceInfo deviceInfo) {
        mConfig = config;
        mDeviceInfo = deviceInfo;
    }

    @Override
    public FailureFlushListener getFailureFlushListener() {
        return mFailureFlushListener;
    }

    @Override
    public CTFeatureFlagsListener getFeatureFlagListener() {
        if (mFeatureFlagListenerWeakReference != null && mFeatureFlagListenerWeakReference.get() != null) {
            return mFeatureFlagListenerWeakReference.get();
        }
        return null;
    }

    @Override
    public InAppActivityListener getInAppActivityListener() {
        return mInAppActivityListener;
    }

    @Override
    public void setFailureFlushListener(final FailureFlushListener failureFlushListener) {
        mFailureFlushListener = failureFlushListener;
    }

    @Override
    public void setFeatureFlagListener(final CTFeatureFlagsListener listener) {
        this.mFeatureFlagListenerWeakReference = new WeakReference<>(listener);
    }

    @Override
    public GeofenceCallback getGeofenceCallback() {
        return geofenceCallback;
    }

    @Override
    public void setGeofenceCallback(final GeofenceCallback geofenceCallback) {
        this.geofenceCallback = geofenceCallback;
    }

    @Override
    public InAppNotificationButtonListener getInAppNotificationButtonListener() {
        if (inAppNotificationButtonListener != null && inAppNotificationButtonListener.get() != null) {
            return inAppNotificationButtonListener.get();
        }
        return null;
    }

    @Override
    public void setInAppActivityListener(
            final InAppActivityListener inAppActivityListener) {
        mInAppActivityListener = inAppActivityListener;
    }

    @Override
    public void setInAppNotificationButtonListener(
            InAppNotificationButtonListener inAppNotificationButtonListener) {
        this.inAppNotificationButtonListener = new WeakReference<>(inAppNotificationButtonListener);
    }

    @Override
    public InAppNotificationListener getInAppNotificationListener() {
        return inAppNotificationListener;
    }

    @Override
    public void setInAppNotificationListener(final InAppNotificationListener inAppNotificationListener) {
        this.inAppNotificationListener = inAppNotificationListener;
    }

    @Override
    public CTInboxListener getInboxListener() {
        return inboxListener;
    }

    @Override
    public void setInboxListener(final CTInboxListener inboxListener) {
        this.inboxListener = inboxListener;
    }

    @Override
    public WeakReference<CTProductConfigListener> getProductConfigListener() {
        return productConfigListener;
    }

    @Override
    public void setProductConfigListener(
            final CTProductConfigListener productConfigListener) {
        if (productConfigListener != null) {
            this.productConfigListener = new WeakReference<>(productConfigListener);
        }
    }

    @Override
    public CTPushAmpListener getPushAmpListener() {
        return pushAmpListener;
    }

    @Override
    public void setPushAmpListener(final CTPushAmpListener pushAmpListener) {
        this.pushAmpListener = pushAmpListener;
    }

    @Override
    public CTPushNotificationListener getPushNotificationListener() {
        return pushNotificationListener;
    }

    @Override
    public void setPushNotificationListener(
            final CTPushNotificationListener pushNotificationListener) {
        this.pushNotificationListener = pushNotificationListener;
    }

    @Override
    public SyncListener getSyncListener() {
        return syncListener;
    }

    @Override
    public void setSyncListener(final SyncListener syncListener) {
        this.syncListener = syncListener;
    }

    //Profile
    @Override
    public void notifyUserProfileInitialized(String deviceID) {
        deviceID = (deviceID != null) ? deviceID : mDeviceInfo.getDeviceID();

        if (deviceID == null) {
            return;
        }

        final SyncListener sl;
        try {
            sl = getSyncListener();
            if (sl != null) {
                sl.profileDidInitialize(deviceID);
            }
        } catch (Throwable t) {
            // Ignore
        }
    }

    @Override
    public void setDisplayUnitListener(DisplayUnitListener listener) {
        if (listener != null) {
            displayUnitListenerWeakReference = new WeakReference<>(listener);
        } else {
            mConfig.getLogger().verbose(mConfig.getAccountId(),
                    Constants.FEATURE_DISPLAY_UNIT + "Failed to set - DisplayUnitListener can't be null");
        }
    }

    void _notifyInboxInitialized() {
        if (this.inboxListener != null) {
            this.inboxListener.inboxDidInitialize();
        }
    }

    @Override
    public void _notifyInboxMessagesDidUpdate() {
        if (this.inboxListener != null) {
            Utils.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (inboxListener != null) {
                        inboxListener.inboxMessagesDidUpdate();
                    }
                }
            });
        }
    }

    /**
     * Notify the registered Display Unit listener about the running Display Unit campaigns
     *
     * @param displayUnits - Array of Display Units {@link CleverTapDisplayUnit}
     */
    void notifyDisplayUnitsLoaded(final ArrayList<CleverTapDisplayUnit> displayUnits) {
        if (displayUnits != null && !displayUnits.isEmpty()) {
            if (displayUnitListenerWeakReference != null && displayUnitListenerWeakReference.get() != null) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //double check to ensure null safety
                        if (displayUnitListenerWeakReference != null
                                && displayUnitListenerWeakReference.get() != null) {
                            displayUnitListenerWeakReference.get().onDisplayUnitsLoaded(displayUnits);
                        }
                    }
                });
            } else {
                mConfig.getLogger().verbose(mConfig.getAccountId(),
                        Constants.FEATURE_DISPLAY_UNIT + "No registered listener, failed to notify");
            }
        } else {
            mConfig.getLogger()
                    .verbose(mConfig.getAccountId(), Constants.FEATURE_DISPLAY_UNIT + "No Display Units found");
        }
    }

    void notifyUserProfileInitialized() {
        notifyUserProfileInitialized(mDeviceInfo.getDeviceID());
    }

}