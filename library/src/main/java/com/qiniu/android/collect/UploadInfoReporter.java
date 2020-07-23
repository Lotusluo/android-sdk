package com.qiniu.android.collect;


import com.qiniu.android.common.ZoneInfo;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.http.metrics.UploadRegionRequestMetrics;
import com.qiniu.android.http.request.RequestTransaction;
import com.qiniu.android.storage.UpToken;
import com.qiniu.android.utils.AsyncRun;
import com.qiniu.android.utils.LogUtil;

import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Date;

public class UploadInfoReporter {

    private ReportConfig config = ReportConfig.getInstance();
    private long lastReportTime = 0;
    private File recordDirectory = new File(config.recordDirectory);
    private File recorderFile = new File(config.recordDirectory + "/qiniu.log");
    private File recorderTempFile = new File(config.recordDirectory + "/qiniuTemp.log");
    private String X_Log_Client_Id;
    private RequestTransaction transaction;

    // 是否正在向服务上报中
    private boolean isReporting = false;

    private static UploadInfoReporter instance = new UploadInfoReporter();

    private UploadInfoReporter(){}

    public static UploadInfoReporter getInstance(){
        return instance;
    }

    public synchronized void report(ReportItem reportItem,
                                    final String tokenString){
        if (reportItem == null){
            return;
        }

        final String jsonString = reportItem.toJson();
        if (!checkReportAvailable() || jsonString == null){
            return;
        }

        AsyncRun.runInBack(new Runnable() {
            @Override
            public void run() {
                synchronized (this){
                    saveReportJsonString(jsonString);
                    reportToServerIfNeeded(tokenString);
                }
            }
        });
    }

    public void clean(){
        cleanRecorderFile();
        cleanTempLogFile();
    }

    private void cleanRecorderFile(){
        if (recorderFile.exists()){
            recorderFile.delete();
        }
    }

    private void cleanTempLogFile(){
        if (recorderTempFile.exists()){
            recorderTempFile.delete();
        }
    }

    private boolean checkReportAvailable(){
        if (!config.isReportEnable){
            return false;
        }
        if (config.maxRecordFileSize <= config.uploadThreshold){
            LogUtil.e("maxRecordFileSize must be larger than uploadThreshold");
            return false;
        }
        return true;
    }

    private void saveReportJsonString(final String jsonString){

        if (!recordDirectory.exists() && !recordDirectory.mkdirs()){
            return;
        }

        if (!recordDirectory.isDirectory()){
            LogUtil.e("recordDirectory is not a directory");
            return;
        }

        if (!recorderFile.exists()){
            try {
                boolean isSuccess = recorderFile.createNewFile();
                if (!isSuccess){
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }

        if (recorderFile.length() > config.maxRecordFileSize){
            return;
        }

        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(recorderFile, true);
            fos.write((jsonString + "\n").getBytes());
            fos.flush();
        } catch (FileNotFoundException ignored) {
        } catch (IOException ignored) {
        } finally {
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private void reportToServerIfNeeded(String tokenString){
        if (isReporting){
            return;
        }
        boolean needToResport = false;
        long currentTime = new Date().getTime();

        if (recorderTempFile.exists()){
            needToResport = true;
        } else if ((recorderFile.length() > config.uploadThreshold)
             && (lastReportTime == 0 || (currentTime - lastReportTime) > config.interval * 60)){
            boolean isSuccess = recorderFile.renameTo(recorderTempFile);
            if (isSuccess) {
                needToResport = true;
            }
        }
        if (needToResport && !this.isReporting){
            reportToServer(tokenString);
        }
    }

    private void reportToServer(String tokenString){

        isReporting = true;

        RequestTransaction transaction = createUploadRequestTransaction(tokenString);
        if (transaction == null){
            return;
        }

        byte[] logData = getLogData();
        if (logData == null && logData.length == 9){
            return;
        }

        transaction.reportLog(logData, X_Log_Client_Id, true, new RequestTransaction.RequestCompleteHandler() {
            @Override
            public void complete(ResponseInfo responseInfo, UploadRegionRequestMetrics requestMetrics, JSONObject response) {
                if (responseInfo.isOK()){
                    lastReportTime = new Date().getTime();
                    if (X_Log_Client_Id == null
                            && responseInfo.responseHeader != null
                            && responseInfo.responseHeader.get("x-log-client-id") != null){
                        X_Log_Client_Id = responseInfo.responseHeader.get("x-log-client-id");
                    }
                    cleanTempLogFile();
                }
                isReporting = false;
            }
        });

    }

    private byte[] getLogData(){
        if (recorderTempFile == null || recorderTempFile.length() == 0){
            return null;
        }

        long length = recorderTempFile.length();
        RandomAccessFile randomAccessFile = null;
        byte[] data = null;
        try {
            randomAccessFile = new RandomAccessFile(recorderTempFile, "r");
            data = new byte[(int)length];
            randomAccessFile.read(data);
        } catch (FileNotFoundException ignored) {
        } catch (IOException e) {
            data = null;
        }
        if (randomAccessFile != null){
            try {
                randomAccessFile.close();
            } catch (IOException e){}
        }
        return data;
    }

    private RequestTransaction createUploadRequestTransaction(String tokenString){
        if (config == null){
            return null;
        }
        UpToken token = UpToken.parse(tokenString);
        if (token == null){
            return null;
        }
        ArrayList<String> hosts = new ArrayList<>();
        hosts.add(config.serverURL);

        ArrayList<String> ioHosts = new ArrayList<>();
        ioHosts.add(ZoneInfo.SDKDefaultIOHost);

        transaction = new RequestTransaction(hosts, ioHosts, token);
        return transaction;
    }

}
