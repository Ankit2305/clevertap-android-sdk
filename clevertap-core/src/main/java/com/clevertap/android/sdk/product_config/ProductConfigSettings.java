package com.clevertap.android.sdk.product_config;

import static com.clevertap.android.sdk.product_config.CTProductConfigConstants.DEFAULT_MIN_FETCH_INTERVAL_SECONDS;
import static com.clevertap.android.sdk.product_config.CTProductConfigConstants.DEFAULT_NO_OF_CALLS;
import static com.clevertap.android.sdk.product_config.CTProductConfigConstants.DEFAULT_WINDOW_LENGTH_MINS;
import static com.clevertap.android.sdk.product_config.CTProductConfigConstants.KEY_LAST_FETCHED_TIMESTAMP;
import static com.clevertap.android.sdk.product_config.CTProductConfigConstants.PRODUCT_CONFIG_MIN_INTERVAL_IN_SECONDS;
import static com.clevertap.android.sdk.product_config.CTProductConfigConstants.PRODUCT_CONFIG_NO_OF_CALLS;
import static com.clevertap.android.sdk.product_config.CTProductConfigConstants.PRODUCT_CONFIG_WINDOW_LENGTH_MINS;

import android.text.TextUtils;
import com.clevertap.android.sdk.CleverTapInstanceConfig;
import com.clevertap.android.sdk.FileUtils;
import com.clevertap.android.sdk.task.CTExecutorFactory;
import com.clevertap.android.sdk.task.OnSuccessListener;
import com.clevertap.android.sdk.task.Task;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.json.JSONException;
import org.json.JSONObject;

class ProductConfigSettings {

    private final CleverTapInstanceConfig config;

    private String guid;

    private final FileUtils mFileUtils;

    private final HashMap<String, String> settingsMap = new HashMap<>();

    ProductConfigSettings(String guid, CleverTapInstanceConfig config, FileUtils fileUtils) {
        this.guid = guid;
        this.config = config;
        mFileUtils = fileUtils;
        initDefaults();
    }

    String getDirName() {
        return CTProductConfigConstants.DIR_PRODUCT_CONFIG + "_" + config.getAccountId() + "_" + guid;
    }

    String getFullPath() {
        return getDirName() + "/" + CTProductConfigConstants.FILE_NAME_CONFIG_SETTINGS;
    }

    String getGuid() {
        return guid;
    }

    void setGuid(final String guid) {
        this.guid = guid;
    }

    JSONObject getJsonObject(final String content) {
        if (!TextUtils.isEmpty(content)) {
            try {
                return new JSONObject(content);
            } catch (JSONException e) {
                e.printStackTrace();
                config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                        "LoadSettings failed: " + e.getLocalizedMessage());
            }
        }
        return null;
    }

    long getLastFetchTimeStampInMillis() {
        long lastFetchedTimeStamp = 0L;
        String value = settingsMap.get(KEY_LAST_FETCHED_TIMESTAMP);
        try {
            if (!TextUtils.isEmpty(value)) {
                lastFetchedTimeStamp = (long) Double.parseDouble(value);
            }
        } catch (Exception e) {
            e.printStackTrace();
            config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                    "GetLastFetchTimeStampInMillis failed: " + e.getLocalizedMessage());
        }
        return lastFetchedTimeStamp;
    }

    synchronized void setLastFetchTimeStampInMillis(long timeStampInMillis) {
        long lastFetchTimeStampInMillis = getLastFetchTimeStampInMillis();
        if (timeStampInMillis >= 0 && lastFetchTimeStampInMillis != timeStampInMillis) {
            settingsMap.put(KEY_LAST_FETCHED_TIMESTAMP, String.valueOf(timeStampInMillis));
            updateConfigToFile();
        }
    }

    long getNextFetchIntervalInSeconds() {
        long minFetchIntervalInSecondsSDK = getMinFetchIntervalInSeconds();
        long minFetchIntervalInSecondsServer = TimeUnit.MINUTES
                .toSeconds(getWindowIntervalInMinutes() / getNoOfCallsInAllowedWindow());
        return Math.max(minFetchIntervalInSecondsServer, minFetchIntervalInSecondsSDK);
    }

    void initDefaults() {
        settingsMap.put(PRODUCT_CONFIG_NO_OF_CALLS, String.valueOf(DEFAULT_NO_OF_CALLS));
        settingsMap.put(PRODUCT_CONFIG_WINDOW_LENGTH_MINS, String.valueOf(DEFAULT_WINDOW_LENGTH_MINS));
        settingsMap.put(KEY_LAST_FETCHED_TIMESTAMP, String.valueOf(0));
        settingsMap.put(PRODUCT_CONFIG_MIN_INTERVAL_IN_SECONDS, String.valueOf(DEFAULT_MIN_FETCH_INTERVAL_SECONDS));
        config.getLogger()
                .verbose(ProductConfigUtil.getLogTag(config), "Settings loaded with default values: " + settingsMap);
    }

    /**
     * loads settings from file.
     * It's a sync call, please make sure to call this from a background thread
     */
    synchronized void loadSettings(FileUtils fileUtils) {
        if (fileUtils == null) {
            throw new IllegalArgumentException("fileutils can't be null");
        }
        try {
            String content = fileUtils.readFromFile(getFullPath());
            JSONObject jsonObject = getJsonObject(content);
            populateMapWithJson(jsonObject);
        } catch (Exception e) {
            e.printStackTrace();
            config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                    "LoadSettings failed while reading file: " + e.getLocalizedMessage());
        }
    }

    void populateMapWithJson(final JSONObject jsonObject) {
        if (jsonObject == null) {
            return;
        }
        Iterator<String> iterator = jsonObject.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (!TextUtils.isEmpty(key)) {
                String value;
                try {
                    Object obj = jsonObject.get(key);
                    value = String.valueOf(obj);
                } catch (Exception e) {
                    e.printStackTrace();
                    config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                            "Failed loading setting for key " + key + " Error: " + e.getLocalizedMessage());
                    continue;
                }
                if (!TextUtils.isEmpty(value)) {
                    settingsMap.put(key, value);
                }
            }
        }
        config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                "LoadSettings completed with settings: " + settingsMap);
    }

    void reset(final FileUtils fileUtils) {
        if (fileUtils == null) {
            throw new IllegalArgumentException("FileUtils can't be null");
        }
        Task<Void> task = CTExecutorFactory.getInstance(config).ioTask();
        task.call(new Callable<Void>() {
            @Override
            public Void call() {
                try {
                    String fileName = getFullPath();
                    fileUtils.deleteFile(fileName);
                    config.getLogger()
                            .verbose(ProductConfigUtil.getLogTag(config), "Deleted settings file" + fileName);
                } catch (Exception e) {
                    e.printStackTrace();
                    config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                            "Error while resetting settings" + e.getLocalizedMessage());
                }
                return null;
            }
        });
        initDefaults();
    }

    void setARPValue(JSONObject arp) {
        if (arp != null) {
            final Iterator<String> keys = arp.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                try {
                    if (!TextUtils.isEmpty(key)) {
                        final Object object = arp.get(key);
                        if (object instanceof Number) {
                            final int update = (int) ((Number) object).doubleValue();
                            if (CTProductConfigConstants.PRODUCT_CONFIG_NO_OF_CALLS.equalsIgnoreCase(key)
                                    || CTProductConfigConstants.PRODUCT_CONFIG_WINDOW_LENGTH_MINS
                                    .equalsIgnoreCase(key)) {
                                setProductConfigValuesFromARP(key, update);
                            }
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                    config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                            "Product Config setARPValue failed " + e.getLocalizedMessage());
                }
            }
        }
    }

    synchronized void setMinimumFetchIntervalInSeconds(long intervalInSeconds) {
        long minFetchIntervalInSeconds = getMinFetchIntervalInSeconds();
        if (intervalInSeconds > 0 && minFetchIntervalInSeconds != intervalInSeconds) {
            settingsMap.put(PRODUCT_CONFIG_MIN_INTERVAL_IN_SECONDS, String.valueOf(intervalInSeconds));
        }
    }

    private long getMinFetchIntervalInSeconds() {
        long minInterVal = DEFAULT_MIN_FETCH_INTERVAL_SECONDS;
        String value = settingsMap.get(PRODUCT_CONFIG_MIN_INTERVAL_IN_SECONDS);
        try {
            if (!TextUtils.isEmpty(value)) {
                minInterVal = (long) Double.parseDouble(value);
            }
        } catch (Exception e) {
            e.printStackTrace();
            config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                    "GetMinFetchIntervalInSeconds failed: " + e.getLocalizedMessage());
        }
        return minInterVal;
    }

    private int getNoOfCallsInAllowedWindow() {
        int noCallsAllowedInWindow = DEFAULT_NO_OF_CALLS;
        String value = settingsMap.get(PRODUCT_CONFIG_NO_OF_CALLS);
        try {
            if (!TextUtils.isEmpty(value)) {
                noCallsAllowedInWindow = (int) Double.parseDouble(value);
            }
        } catch (Exception e) {
            e.printStackTrace();
            config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                    "GetNoOfCallsInAllowedWindow failed: " + e.getLocalizedMessage());
        }
        return noCallsAllowedInWindow;
    }

    private synchronized void setNoOfCallsInAllowedWindow(int callsInAllowedWindow) {
        long noOfCallsInAllowedWindow = getNoOfCallsInAllowedWindow();
        if (callsInAllowedWindow > 0 && noOfCallsInAllowedWindow != callsInAllowedWindow) {
            settingsMap.put(PRODUCT_CONFIG_NO_OF_CALLS, String.valueOf(callsInAllowedWindow));
            updateConfigToFile();
        }
    }

    private int getWindowIntervalInMinutes() {
        int windowIntervalInMinutes = DEFAULT_WINDOW_LENGTH_MINS;
        String value = settingsMap.get(PRODUCT_CONFIG_WINDOW_LENGTH_MINS);
        try {
            if (!TextUtils.isEmpty(value)) {
                windowIntervalInMinutes = (int) Double.parseDouble(value);
            }
        } catch (Exception e) {
            e.printStackTrace();
            config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                    "GetWindowIntervalInMinutes failed: " + e.getLocalizedMessage());
        }
        return windowIntervalInMinutes;
    }

    private synchronized void setWindowIntervalInMinutes(int intervalInMinutes) {
        int windowIntervalInMinutes = getWindowIntervalInMinutes();
        if (intervalInMinutes > 0 && windowIntervalInMinutes != intervalInMinutes) {
            settingsMap.put(PRODUCT_CONFIG_WINDOW_LENGTH_MINS, String.valueOf(intervalInMinutes));
            updateConfigToFile();
        }
    }

    private void setProductConfigValuesFromARP(String key, int value) {
        switch (key) {
            case CTProductConfigConstants.PRODUCT_CONFIG_NO_OF_CALLS:
                setNoOfCallsInAllowedWindow(value);
                break;
            case CTProductConfigConstants.PRODUCT_CONFIG_WINDOW_LENGTH_MINS:
                setWindowIntervalInMinutes(value);
                break;
        }
    }

    private synchronized void updateConfigToFile() {

        Task<Boolean> task = CTExecutorFactory.getInstance(config).ioTask();
        task.addOnSuccessListener(new OnSuccessListener<Boolean>() {
            @Override
            public void onSuccess(final Boolean isSuccess) {
                if (isSuccess) {
                    config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                            "Product Config settings: writing Success " + settingsMap);
                } else {
                    config.getLogger()
                            .verbose(ProductConfigUtil.getLogTag(config),
                                    "Product Config settings: writing Failed");
                }
            }
        }).call(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                try {
                    //Ensure that we are not saving min interval in seconds
                    HashMap<String, String> toWriteMap = new HashMap<>(settingsMap);
                    toWriteMap.remove(PRODUCT_CONFIG_MIN_INTERVAL_IN_SECONDS);

                    mFileUtils.writeJsonToFile(getDirName(),
                            CTProductConfigConstants.FILE_NAME_CONFIG_SETTINGS, new JSONObject(toWriteMap));
                } catch (Exception e) {
                    e.printStackTrace();
                    config.getLogger().verbose(ProductConfigUtil.getLogTag(config),
                            "UpdateConfigToFile failed: " + e.getLocalizedMessage());
                    return false;
                }
                return true;
            }
        });
    }
}