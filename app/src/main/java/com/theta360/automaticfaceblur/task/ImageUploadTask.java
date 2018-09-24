/**
 * Copyright 2018 Ricoh Company, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.theta360.automaticfaceblur.task;

import android.os.AsyncTask;

import com.google.gson.GsonBuilder;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.theta360.automaticfaceblur.network.HttpConnector;
import com.theta360.automaticfaceblur.network.model.requests.CommandsRequest;
import com.theta360.automaticfaceblur.network.model.values.Errors;
import com.theta360.automaticfaceblur.view.MJpegInputStream;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import timber.log.Timber;

import static com.theta360.automaticfaceblur.MainActivity.DCIM;

/**
 * ImageUploadTask
 */

public class ImageUploadTask extends AsyncTask<Void, Void, String> {

    Callback mCallback;
    private AsyncHttpServerResponse mResponse;
    private CommandsRequest mCommandsRequest;

    /**
     * Constructor of ImageUploadTask.
     *
     * @param callback ImageUploadTask Callback
     * @param response AsyncHttpServerResponse response
     * @param commandsRequest CommandsRequest
     */
    public ImageUploadTask(ImageUploadTask.Callback callback, AsyncHttpServerResponse response,
                           CommandsRequest commandsRequest) {
        this.mCallback = callback;
        this.mResponse = response;
        this.mCommandsRequest = commandsRequest;
    }

    /**
     * Upload image.
     *
     * @return upload request results
     */
    @Override
    protected String doInBackground(Void... aVoid) {
        String commands = new GsonBuilder().create().toJson(mCommandsRequest);
        String uploadUrl;
        String fileUrl;
        String result = null;

        try {
            JSONObject json = new JSONObject(commands);
            JSONObject jsonParameters = json.optJSONObject("parameters");
            if (jsonParameters == null){
                return null;
            }
            fileUrl = (String) jsonParameters.get("fileUrl");
            uploadUrl = (String) jsonParameters.get("uploadUrl");

            //setup params
            Map<String, String> params = new HashMap<>(2);
/*            params.put("foo", "hash");
            params.put("bar", "caption");*/

            Matcher matcher = Pattern.compile("/\\d{3}RICOH.*").matcher(fileUrl);
            if (matcher.find()) {
                String filepath = DCIM + matcher.group();
                result = multipartRequest(uploadUrl, params, filepath, "file", "image/jpeg");

            }

        } catch (JSONException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return result;

    }

    /**
     * Notify error.
     *
     * @param responseData
     */
    @Override
    protected void onPostExecute(String responseData) {
        if (responseData == null) {
            mCallback.onSendCommand(responseData,mResponse, mCommandsRequest, Errors.INVALID_PARAMETER_VALUE);
        } else {
            mCallback.onSendCommand(responseData,mResponse, mCommandsRequest, null);
        }
    }

    /**
     * Interface of Callback.
     */
    public interface Callback {
        void onSendCommand(String responseData, AsyncHttpServerResponse response, CommandsRequest commandsRequest, Errors errors);
    }

    /**
     * Multipart upload api.
     */

    public String multipartRequest(String urlTo, Map<String, String> params, String filepath, String filefield, String fileMimeType) throws IOException {
        HttpURLConnection connection = null;
        DataOutputStream outputStream = null;
        InputStream inputStream = null;

        String twoHyphens = "--";
        String boundary = "*****" + Long.toString(System.currentTimeMillis()) + "*****";
        String lineEnd = "\r\n";

        String result = "";

        int bytesRead, bytesAvailable, bufferSize;
        byte[] buffer;
        int maxBufferSize = 1 * 1024 * 1024;

        String[] q = filepath.split("/");
        int idx = q.length - 1;

        try {
            File file = new File(filepath);
            String filename = file.getName();
            FileInputStream fileInputStream = new FileInputStream(file);

            URL url = new URL(urlTo);
            connection = (HttpURLConnection) url.openConnection();

            connection.setDoInput(true);
            connection.setDoOutput(true);
            connection.setUseCaches(false);

            connection.setRequestMethod("POST");
            connection.setRequestProperty("Connection", "Keep-Alive");
            connection.setRequestProperty("User-Agent", "Android Multipart HTTP Client 1.0");
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("Accept", "*/*");

            outputStream = new DataOutputStream(connection.getOutputStream());
            outputStream.writeBytes(twoHyphens + boundary + lineEnd);
            outputStream.writeBytes("Content-Disposition: form-data; name=\"" + filefield + "\"; filename=\"" + filename + "\"" + lineEnd);
            outputStream.writeBytes("Content-Type: " + fileMimeType + lineEnd);
            outputStream.writeBytes("Content-Transfer-Encoding: binary" + lineEnd);

            outputStream.writeBytes(lineEnd);

            bytesAvailable = fileInputStream.available();
            bufferSize = Math.min(bytesAvailable, maxBufferSize);
            buffer = new byte[bufferSize];

            bytesRead = fileInputStream.read(buffer, 0, bufferSize);
            while (bytesRead > 0) {
                outputStream.write(buffer, 0, bufferSize);
                bytesAvailable = fileInputStream.available();
                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                bytesRead = fileInputStream.read(buffer, 0, bufferSize);
            }

            outputStream.writeBytes(lineEnd);

            // Upload POST Data
            Iterator<String> keys = params.keySet().iterator();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = params.get(key);

                outputStream.writeBytes(twoHyphens + boundary + lineEnd);
                outputStream.writeBytes("Content-Disposition: form-data; name=\"" + key + "\"" + lineEnd);
                outputStream.writeBytes("Content-Type: text/plain" + lineEnd);
                outputStream.writeBytes(lineEnd);
                outputStream.writeBytes(value);
                outputStream.writeBytes(lineEnd);
            }

            outputStream.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);


            if (202 != connection.getResponseCode()) {
                throw new IOException("Failed to upload code:" + connection.getResponseCode() + " " + connection.getResponseMessage());
            }

            inputStream = connection.getInputStream();

            result = this.convertStreamToString(inputStream);

            fileInputStream.close();
            inputStream.close();
            outputStream.flush();
            outputStream.close();

            return result;
        } catch (Exception e) {
            Timber.d(e.getMessage());
            throw new IOException(e);
        }

    }

    private String convertStreamToString(InputStream is) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();

        String line = null;
        try {
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return sb.toString();
    }


}
