package com.clevertap.android.sdk;

import android.content.Context;
import com.clevertap.android.sdk.featureFlags.CTFeatureFlagsFactory;
import com.clevertap.android.sdk.db.DBManager;
import com.clevertap.android.sdk.events.EventMediator;
import com.clevertap.android.sdk.events.EventQueueManager;
import com.clevertap.android.sdk.inapp.InAppController;
import com.clevertap.android.sdk.login.LoginController;
import com.clevertap.android.sdk.network.NetworkManager;
import com.clevertap.android.sdk.pushnotification.PushProviders;
import com.clevertap.android.sdk.task.MainLooperHandler;
import com.clevertap.android.sdk.task.PostAsyncSafelyHandler;
import com.clevertap.android.sdk.validation.ValidationResultStack;
import com.clevertap.android.sdk.validation.Validator;

class CleverTapFactory {

    //TODO piyush check it in builder
    static CoreState getCoreState(Context context, CleverTapInstanceConfig cleverTapInstanceConfig,
            String cleverTapID) {
        CoreState coreState = new CoreState(context);

        CoreMetaData coreMetaData = new CoreMetaData();
        coreState.setCoreMetaData(coreMetaData);

        Validator validator = new Validator();
        coreState.setValidator(validator);

        ValidationResultStack validationResultStack = new ValidationResultStack();
        coreState.setValidationResultStack(validationResultStack);

        CTLockManager ctLockManager = new CTLockManager();
        coreState.setCTLockManager(ctLockManager);

        MainLooperHandler mainLooperHandler = new MainLooperHandler();
        coreState.setMainLooperHandler(mainLooperHandler);

        CleverTapInstanceConfig config = new CleverTapInstanceConfig(cleverTapInstanceConfig);
        coreState.setConfig(config);

        EventMediator eventMediator = new EventMediator(context, config, coreMetaData);
        coreState.setEventMediator(eventMediator);

        PostAsyncSafelyHandler postAsyncSafelyHandler = new PostAsyncSafelyHandler(config);
        coreState.setPostAsyncSafelyHandler(postAsyncSafelyHandler);

        LocalDataStore localDataStore = new LocalDataStore(context, config);
        coreState.setLocalDataStore(localDataStore);

        DeviceInfo deviceInfo = new DeviceInfo(context, config, cleverTapID, coreMetaData);
        coreState.setDeviceInfo(deviceInfo);

        BaseCallbackManager callbackManager = new CallbackManager(config, deviceInfo);
        coreState.setCallbackManager(callbackManager);

        SessionManager sessionManager = new SessionManager(config, coreMetaData, validator, localDataStore);
        coreState.setSessionManager(sessionManager);

        InAppFCManager inAppFCManager = null;
        if (coreState.getDeviceInfo() != null && coreState.getDeviceInfo().getDeviceID() != null) {
            inAppFCManager = new InAppFCManager(context, config, coreState.getDeviceInfo().getDeviceID());
            coreState.getConfig().getLogger()
                    .verbose("Initializing InAppFC with device Id = " + coreState.getDeviceInfo().getDeviceID());
            coreState.setInAppFCManager(inAppFCManager);
        }

        DBManager baseDatabaseManager = new DBManager(config, ctLockManager);
        coreState.setDatabaseManager(baseDatabaseManager);

        ControllerManager controllerManager = new ControllerManager(context, config, postAsyncSafelyHandler,
                ctLockManager, callbackManager, deviceInfo, baseDatabaseManager);
        coreState.setControllerManager(controllerManager);

        NetworkManager networkManager = new NetworkManager(context, config, deviceInfo, coreMetaData,
                validationResultStack, controllerManager, inAppFCManager, baseDatabaseManager,
                postAsyncSafelyHandler, callbackManager, ctLockManager, validator, localDataStore);
        coreState.setNetworkManager(networkManager);

        EventQueueManager baseEventQueueManager = new EventQueueManager(baseDatabaseManager, context, config,
                eventMediator,
                sessionManager, callbackManager,
                mainLooperHandler, postAsyncSafelyHandler, deviceInfo, validationResultStack,
                networkManager, coreMetaData, ctLockManager, localDataStore);
        coreState.setBaseEventQueueManager(baseEventQueueManager);

        AnalyticsManager analyticsManager = new AnalyticsManager(context, config, baseEventQueueManager, validator,
                validationResultStack, coreMetaData, postAsyncSafelyHandler, localDataStore, deviceInfo,
                mainLooperHandler, callbackManager, controllerManager);
        coreState.setAnalyticsManager(analyticsManager);

        InAppController inAppController = new InAppController(context, config, mainLooperHandler,
                postAsyncSafelyHandler, inAppFCManager, callbackManager, analyticsManager, coreMetaData);
        coreState.setInAppController(inAppController);
        coreState.getControllerManager().setInAppController(inAppController);

        initFeatureFlags(context, coreState, config, deviceInfo, callbackManager, analyticsManager);

        LocationManager locationManager = new LocationManager(context, config, coreMetaData, baseEventQueueManager);
        coreState.setLocationManager(locationManager);

        PushProviders pushProviders = PushProviders
                .load(context, config, baseDatabaseManager, postAsyncSafelyHandler, validationResultStack,
                        analyticsManager, controllerManager);
        coreState.setPushProviders(pushProviders);

        ActivityLifeCycleManager activityLifeCycleManager = new ActivityLifeCycleManager(context, config,
                analyticsManager, coreMetaData, sessionManager, pushProviders, callbackManager, inAppController,
                baseEventQueueManager, postAsyncSafelyHandler);
        coreState.setActivityLifeCycleManager(activityLifeCycleManager);

        LoginController loginController = new LoginController(context, config, deviceInfo,
                validationResultStack, baseEventQueueManager, analyticsManager, inAppFCManager,
                postAsyncSafelyHandler, coreMetaData, controllerManager, sessionManager,
                localDataStore, callbackManager, baseDatabaseManager, ctLockManager);
        coreState.setLoginController(loginController);
        return coreState;
    }

    static void initFeatureFlags(Context context, CoreState coreState, CleverTapInstanceConfig config,
            DeviceInfo deviceInfo, BaseCallbackManager callbackManager, AnalyticsManager analyticsManager) {
        Logger.v("Initializing Feature Flags with device Id = " + deviceInfo.getDeviceID());
        if (config.isAnalyticsOnly()) {
            config.getLogger().debug(config.getAccountId(), "Feature Flag is not enabled for this instance");
        } else {
            coreState.getControllerManager().setCTFeatureFlagsController(CTFeatureFlagsFactory.getInstance(context,
                    deviceInfo.getDeviceID(),
                    config, callbackManager, analyticsManager));
            config.getLogger().verbose(config.getAccountId(), "Feature Flags initialized");
        }
    }
}