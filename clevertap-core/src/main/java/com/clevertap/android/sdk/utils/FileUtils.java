package com.clevertap.android.sdk.utils;

import android.content.Context;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import com.clevertap.android.sdk.CleverTapInstanceConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import org.json.JSONObject;

@RestrictTo(Scope.LIBRARY_GROUP)
public class FileUtils {

    private final CleverTapInstanceConfig mConfig;

    private final Context mContext;

    public FileUtils(@NonNull final Context context, @NonNull final CleverTapInstanceConfig config) {
        mContext = context;
        mConfig = config;
    }

    public void deleteDirectory(String dirName) {
        if (TextUtils.isEmpty(dirName)) {
            return;
        }
        try {
            synchronized (FileUtils.class) {
                File file = new File(mContext.getFilesDir(), dirName);
                if (file.exists() && file.isDirectory()) {
                    String[] children = file.list();
                    for (String child : children) {
                        boolean deleted = new File(file, child).delete();
                        mConfig.getLogger().verbose(mConfig.getAccountId(),
                                "File" + child + " isDeleted:" + deleted);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            mConfig.getLogger().verbose(mConfig.getAccountId(),
                    "writeFileOnInternalStorage: failed" + dirName + " Error:" + e.getLocalizedMessage());
        }
    }

    public void deleteFile(String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return;
        }
        try {
            synchronized (FileUtils.class) {
                File file = new File(mContext.getFilesDir(), fileName);
                if (file.exists()) {
                    if (file.delete()) {
                        mConfig.getLogger().verbose(mConfig.getAccountId(), "File Deleted:" + fileName);
                    } else {
                        mConfig.getLogger().verbose(mConfig.getAccountId(), "Failed to delete file" + fileName);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            mConfig.getLogger().verbose(mConfig.getAccountId(),
                    "writeFileOnInternalStorage: failed" + fileName + " Error:" + e.getLocalizedMessage());
        }
    }

    public String readFromFile(String fileNameWithPath) {

        String content = "";
        //Make sure to use a try-catch statement to catch any errors
        try {
            //Make your FilePath and File
            String yourFilePath = mContext.getFilesDir() + "/" + fileNameWithPath;
            File yourFile = new File(yourFilePath);
            //Make an InputStream with your File in the constructor
            InputStream inputStream = new FileInputStream(yourFile);
            StringBuilder stringBuilder = new StringBuilder();
            //Check to see if your inputStream is null
            //If it isn't use the inputStream to make a InputStreamReader
            //Use that to make a BufferedReader
            //Also create an empty String
            InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            String receiveString;
            //Use a while loop to append the lines from the Buffered reader
            while ((receiveString = bufferedReader.readLine()) != null) {
                stringBuilder.append(receiveString);
            }
            //Close your InputStream and save stringBuilder as a String
            inputStream.close();
            content = stringBuilder.toString();
        } catch (Exception e) {
            mConfig.getLogger()
                    .verbose(mConfig.getAccountId(), "[Exception While Reading: " + e.getLocalizedMessage());
            //Log your error with Log.e
        }
        return content;
    }

    public void writeJsonToFile(String dirName,
            String fileName, JSONObject jsonObject) {
        try {
            if (jsonObject == null || TextUtils.isEmpty(dirName) || TextUtils.isEmpty(fileName)) {
                return;
            }
            synchronized (FileUtils.class) {
                File file = new File(mContext.getFilesDir(), dirName);
                if (!file.exists()) {
                    if (!file.mkdir()) {
                        return;// if directory is not created don't proceed and return
                    }
                }

                File file1 = new File(file, fileName);
                FileWriter writer = new FileWriter(file1, false);
                writer.append(jsonObject.toString());
                writer.flush();
                writer.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            mConfig.getLogger().verbose(mConfig.getAccountId(),
                    "writeFileOnInternalStorage: failed" + e.getLocalizedMessage());
        }
    }
}