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
package com.theta360.automaticfaceblur;

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.KeyEvent;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.theta360.automaticfaceblur.network.WebServer;
import com.theta360.automaticfaceblur.network.model.commands.CommandsName;
import com.theta360.automaticfaceblur.network.model.objects.ProgressObject;
import com.theta360.automaticfaceblur.network.model.requests.CommandsRequest;
import com.theta360.automaticfaceblur.network.model.responses.CommandsResponse;
import com.theta360.automaticfaceblur.network.model.responses.StatusResponse;
import com.theta360.automaticfaceblur.network.model.values.Errors;
import com.theta360.automaticfaceblur.network.model.values.State;
import com.theta360.automaticfaceblur.network.model.values.Status;
import com.theta360.automaticfaceblur.task.CheckImageStatusTask;
import com.theta360.automaticfaceblur.task.GetOptionsTask;
import com.theta360.automaticfaceblur.task.ImageProcessorTask;
import com.theta360.automaticfaceblur.task.ImageUploadTask;
import com.theta360.automaticfaceblur.task.SetOptionsTask;
import com.theta360.automaticfaceblur.task.ShowLiveViewTask;
import com.theta360.automaticfaceblur.task.TakePictureTask;
import com.theta360.automaticfaceblur.task.TakePictureTask.Callback;
import com.theta360.automaticfaceblur.task.UpdatePreviewTask;
import com.theta360.automaticfaceblur.view.MJpegInputStream;
import com.theta360.pluginlibrary.activity.PluginActivity;
import com.theta360.pluginlibrary.callback.KeyCallback;
import com.theta360.pluginlibrary.receiver.KeyReceiver;
import com.theta360.pluginlibrary.values.LedColor;
import com.theta360.pluginlibrary.values.LedTarget;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import timber.log.Timber;

/**
 * MainActivity
 */
public class MainActivity extends PluginActivity {
    public static final String DCIM = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DCIM).getPath();
    private TakePictureTask mTakePictureTask;
    private ImageProcessorTask mImageProcessorTask;
    private ImageUploadTask mImageUploadTask;
    private byte[] mPreviewByteArray;
    private SetOptionsTask mSetOptionsTask;
    private GetOptionsTask mGetOptionsTask;
    private CheckImageStatusTask mCheckImageStatusTask;
    private WebServer mWebServer;
    private UpdatePreviewTask mUpdatePreviewTask;

    /**
     * Set a KeyCallback when onCreate executes.
     *
     * @param savedInstanceState savedInstanceState
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setKeyCallback(new KeyCallback() {
            /**
             * Receive the shutter key down when it is not during taking picture task or
             * processing image task.
             * @param keyCode code of key
             * @param keyEvent event of key
             */
            @Override
            public void onKeyDown(int keyCode, KeyEvent keyEvent) {
                if (keyCode == KeyReceiver.KEYCODE_CAMERA) {
                    if (mTakePictureTask == null && mImageProcessorTask == null) {
                        if (mUpdatePreviewTask != null) {
                            mUpdatePreviewTask.cancel(false);
                        }
                        mTakePictureTask = new TakePictureTask(mTakePictureTaskCallback, null,
                                null);
                        mTakePictureTask.execute();
                    }
                }
            }

            @Override
            public void onKeyUp(int i, KeyEvent keyEvent) {

            }

            @Override
            public void onKeyLongPress(int keyCode, KeyEvent event) {

            }
        });
    }

    /**
     * Control LEDs when onResume executes.
     */
    @Override
    protected void onResume() {
        Timber.d("onResume");
        super.onResume();
        controlLedOnCreate();
        mWebServer = new WebServer(getApplicationContext(), null, mWebServerCallback);
    }

    /**
     * Cancel async tasks and stop the web server when onPause executes.
     */
    @Override
    protected void onPause() {
        if (mTakePictureTask != null) {
            mTakePictureTask.cancel(true);
            mTakePictureTask = null;
        }
        if (mImageProcessorTask != null) {
            mImageProcessorTask.cancel(true);
            mImageProcessorTask = null;
        }
        if (mUpdatePreviewTask != null) {
            mUpdatePreviewTask.cancel(false);
            mUpdatePreviewTask = null;
        }
        mWebServer.stop();
        mCanFinishPlugin = true;
        super.onPause();
    }

    /**
     * TakePictureTask Callback.
     */
    TakePictureTask.Callback mTakePictureTaskCallback = new Callback() {
        @Override
        public void onPreExecute() {
            mCanFinishPlugin = false;
        }

        @Override
        public void onPictureGenerated(String fileUrl) {
            if (!TextUtils.isEmpty(fileUrl)) {
                notificationAudioOpen();
                notificationLedBlink(LedTarget.LED4, LedColor.BLUE, 1000);
                mImageProcessorTask = new ImageProcessorTask(mImageProcessorTaskCallback);
                mImageProcessorTask.execute(fileUrl);
            } else {
                notificationError(getResources().getString(R.string.take_picture_error));
            }
            mTakePictureTask = null;
        }

        @Override
        public void onSendCommand(String responseData, AsyncHttpServerResponse response, CommandsRequest commandsRequest,
                Errors errors) {
            if (mWebServer != null && response != null && commandsRequest != null) {
                CommandsName commandsName = commandsRequest.getCommandsName();
                if (errors == null) {
                    CommandsResponse commandsResponse = new CommandsResponse(commandsName,
                            State.IN_PROGRESS);
                    commandsResponse.setProgress(new ProgressObject(0.00));
                    mWebServer.sendTakePictureResponse(response, commandsResponse, responseData);
                } else {
                    mWebServer.sendError(response, errors, commandsName);
                }
            }
        }

        @Override
        public void onCompleted() {
            mCanFinishPlugin = true;
        }

        @Override
        public void onTakePictureFailed() {
            notificationError(getResources().getString(R.string.error));
            mCanFinishPlugin = true;
        }
    };

    /**
     * SetOptionsTask Callback.
     */
    SetOptionsTask.Callback mSetOptionsTaskCallback = new SetOptionsTask.Callback() {
        @Override
        public void onSendCommand(AsyncHttpServerResponse response, CommandsRequest commandsRequest,
                Errors errors) {
            if (mWebServer != null && response != null && commandsRequest != null) {
                CommandsName commandsName = commandsRequest.getCommandsName();
                if (errors == null) {
                    CommandsResponse commandsResponse = new CommandsResponse(commandsName,
                            State.DONE);
                    mWebServer.sendCommandsResponse(response, commandsResponse);
                } else {
                    mWebServer.sendError(response, errors, commandsName);
                }
                mSetOptionsTask = null;
            }
        }
    };

    /**
     * GetOptionsTask Callback.
     */
    GetOptionsTask.Callback mGetOptionsTaskCallback = new GetOptionsTask.Callback() {
        @Override
        public void onSendCommand(String responseData, AsyncHttpServerResponse response,
                CommandsRequest commandsRequest,
                Errors errors) {
            if (mWebServer != null && response != null && commandsRequest != null) {
                CommandsName commandsName = commandsRequest.getCommandsName();
                if (errors == null) {
                    mWebServer.sendGetOptionsResponse(response, responseData);
                } else {
                    mWebServer.sendError(response, errors, commandsName);
                }
                mGetOptionsTask = null;
            }
        }
    };

    /**
     * CheckImageStatusTask Callback.
     */
    CheckImageStatusTask.Callback mCheckImageStatusTaskCallback = new CheckImageStatusTask.Callback() {
        @Override
        public void onSendCommand(String responseData, AsyncHttpServerResponse response,
                                  CommandsRequest commandsRequest,
                                  Errors errors) {
            if (mWebServer != null && response != null && commandsRequest != null) {
                CommandsName commandsName = commandsRequest.getCommandsName();
                if (errors == null) {
                    mWebServer.sendCheckStatusResponse(response, responseData);
                } else {
                    mWebServer.sendError(response, errors, commandsName);
                }
                mCheckImageStatusTask = null;
            }
        }
    };

    /**
     * ImageUploadTask Callback.
     */
    ImageUploadTask.Callback mImageUploadTaskCallback = new ImageUploadTask.Callback() {
        @Override
        public void onSendCommand(String responseData, AsyncHttpServerResponse response,
                                  CommandsRequest commandsRequest,
                                  Errors errors) {
            if (mWebServer != null && response != null && commandsRequest != null) {
                CommandsName commandsName = commandsRequest.getCommandsName();
                if (errors == null) {
                    CommandsResponse commandsResponse = new CommandsResponse(commandsName,
                            State.DONE);
                    mWebServer.sendCommandsResponse(response, commandsResponse);
                } else {
                    mWebServer.sendError(response, errors, commandsName);
                }
                mImageUploadTask = null;
            }
        }
    };

    /**
     * ShowLiveViewTask Callback.
     */
    ShowLiveViewTask.Callback mShowLiveViewTaskCallback = new ShowLiveViewTask.Callback() {
        @Override
        public void onLivePreview(MJpegInputStream mJpegInputStream,
                AsyncHttpServerResponse response, CommandsRequest commandsRequest,
                Errors errors) {
            CommandsName commandsName = CommandsName.START_LIVE_PREVIEW;
            if (errors == null) {
               if (mUpdatePreviewTask != null) {
                   mUpdatePreviewTask.cancel(false);
               }

               mUpdatePreviewTask = new UpdatePreviewTask(mSendPreviewTaskCallback,
                       mJpegInputStream);
               mUpdatePreviewTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                CommandsResponse commandsResponse = new CommandsResponse(commandsName,
                        State.DONE);
                mWebServer.sendCommandsResponse(response, commandsResponse);
            } else {
                mWebServer.sendError(response, errors, commandsName);
            }
        }
    };

    /**
     * UpdatePreviewTask Callback.
     */
    public UpdatePreviewTask.Callback mSendPreviewTaskCallback = new UpdatePreviewTask.Callback() {
        @Override
        public void updatePreview(byte[] previewByteArray) {
            mPreviewByteArray = previewByteArray;
        }

        @Override
        public void onCancelled() {
            mUpdatePreviewTask = null;
        }
    };

    /**
     * ImageProcessorTask Callback.
     */
    private ImageProcessorTask.Callback mImageProcessorTaskCallback = new ImageProcessorTask.Callback() {
        @Override
        public void onSuccess(Map<String, String> fileUrlMap) {
            String fileUrl = fileUrlMap.get(ImageProcessorTask.ORIGINAL_FILE_KEY);
            Matcher notBlurredFileMatcher = Pattern.compile("/DCIM.*").matcher(fileUrl);
            if (notBlurredFileMatcher.find()) {
                fileUrl = notBlurredFileMatcher.group();
            }

            Matcher blurredMatcher = Pattern.compile("/DCIM.*")
                    .matcher(fileUrlMap.get(ImageProcessorTask.BLURRED_FILE_KEY));
            if (blurredMatcher.find()) {
                String formattedFileUrl = blurredMatcher.group();
                Timber.d(formattedFileUrl);
                Timber.d(fileUrl);
                String[] fileUrls = new String[]{formattedFileUrl, fileUrl};
                notificationDatabaseUpdate(fileUrls);
            }
            mImageProcessorTask = null;
            notificationAudioClose();
            notificationLedShow(LedTarget.LED4);
        }

        @Override
        public void onError(boolean isCancelled) {
            mImageProcessorTask = null;
            if (isCancelled) {
                notificationLedShow(LedTarget.LED4);
            } else {
                notificationError(getResources().getString(R.string.error));
            }
        }
    };

    /**
     * WebServer Callback
     */
    private WebServer.Callback mWebServerCallback = new WebServer.Callback() {

        @Override
        public void commandsRequest(AsyncHttpServerResponse response,
                CommandsRequest commandsRequest) {
            CommandsName commandsName = commandsRequest.getCommandsName();
            Timber.d("commandsName : %s", commandsName.toString());
            switch (commandsName) {
                case TAKE_PICTURE:
                    if (mTakePictureTask == null && mImageProcessorTask == null
                            && mSetOptionsTask == null && mGetOptionsTask == null) {
                        if (mUpdatePreviewTask != null) {
                            mUpdatePreviewTask.cancel(false);
                        }
                        mTakePictureTask = new TakePictureTask(mTakePictureTaskCallback, response,
                                commandsRequest);
                        mTakePictureTask.execute();
                    } else {
                        mWebServer.sendError(response, Errors.DEVICE_BUSY, commandsName);
                        mTakePictureTask = null;
                    }
                    break;
                case SET_OPTIONS:
                    if (mTakePictureTask == null && mImageProcessorTask == null
                            && mSetOptionsTask == null) {
                        mSetOptionsTask = new SetOptionsTask(mSetOptionsTaskCallback, response,
                                commandsRequest);
                        mSetOptionsTask.execute();
                    } else {
                        mWebServer.sendError(response, Errors.DEVICE_BUSY, commandsName);
                        mSetOptionsTask = null;
                    }
                    break;
                case GET_OPTIONS:
                    if (mTakePictureTask == null && mImageProcessorTask == null
                            && mGetOptionsTask == null) {
                        mGetOptionsTask = new GetOptionsTask(mGetOptionsTaskCallback, response,
                                commandsRequest);
                        mGetOptionsTask.execute();
                    } else {
                        mWebServer.sendError(response, Errors.DEVICE_BUSY, commandsName);
                        mGetOptionsTask = null;
                    }
                    break;
                case GET_LIVE_PREVIEW:
                    mWebServer.sendPreviewPicture(response, mPreviewByteArray);
                    break;
                case START_LIVE_PREVIEW:
                    new ShowLiveViewTask(mShowLiveViewTaskCallback, response,
                            commandsRequest).execute();
                    break;
                case GET_STATUS:
                    if (mTakePictureTask == null && mImageProcessorTask == null) {
                        mWebServer.sendStatus(response, new StatusResponse(Status.IDLE));
                    } else if (mImageProcessorTask == null) {
                        mWebServer.sendStatus(response, new StatusResponse(Status.SHOOTING));
                    } else if (mTakePictureTask == null) {
                        mWebServer.sendStatus(response, new StatusResponse(Status.BLURRING));
                    } else {
                        mWebServer.sendError(response, Errors.DEVICE_BUSY, commandsName);
                    }
                    break;
                case CHECK_IMAGE_STATUS:
                    if (mTakePictureTask == null && mImageProcessorTask == null
                            && mGetOptionsTask == null) {
                        mCheckImageStatusTask = new CheckImageStatusTask(mCheckImageStatusTaskCallback, response,
                                commandsRequest);
                        mCheckImageStatusTask.execute();
                    } else {
                        mWebServer.sendError(response, Errors.DEVICE_BUSY, commandsName);
                        mCheckImageStatusTask = null;
                    }
                    break;
                case UPLOAD_IMAGE:
                    if (mTakePictureTask == null && mImageProcessorTask == null
                            && mGetOptionsTask == null && mImageUploadTask == null) {
                        mImageUploadTask = new ImageUploadTask(mImageUploadTaskCallback, response,
                                commandsRequest);
                        mImageUploadTask.execute();
                    } else {
                        mWebServer.sendError(response, Errors.DEVICE_BUSY, commandsName);
                        mImageUploadTask = null;
                    }
                    break;
                default:
                    mWebServer.sendUnknownCommand(response);
                    break;
            }
        }
    };

    /**
     * Control led when onCreate executes.
     */
    private void controlLedOnCreate() {
        notificationLedShow(LedTarget.LED4);
        notificationLedHide(LedTarget.LED5);
        notificationLedHide(LedTarget.LED6);
        notificationLedHide(LedTarget.LED7);
        notificationLedHide(LedTarget.LED8);
    }
}
