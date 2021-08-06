// Copyright 2018 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.download;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.VisibleForTesting;

import org.jni_zero.CalledByNative;
import org.jni_zero.NativeMethods;

import org.chromium.chrome.browser.download.DownloadLocationDialogMetrics.DownloadLocationSuggestionEvent;
import org.chromium.chrome.browser.download.dialogs.DownloadDialogUtils;
import org.chromium.chrome.browser.download.dialogs.DownloadLocationDialogController;
import org.chromium.chrome.browser.download.dialogs.DownloadLocationDialogCoordinator;
import org.chromium.chrome.browser.download.interstitial.NewDownloadTab;
import org.chromium.chrome.browser.download.settings.DownloadSettings;
import org.chromium.chrome.browser.flags.ChromeFeatureList;
import org.chromium.chrome.browser.preferences.Pref;
import org.chromium.components.prefs.PrefService;
import org.chromium.net.ConnectionType;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.ui.modaldialog.ModalDialogManager;
import org.chromium.ui.modaldialog.ModalDialogManagerHolder;

/** Glues download dialogs UI code and handles the communication to download native backend. */
public class DownloadDialogBridge implements DownloadLocationDialogController {
    private long mNativeDownloadDialogBridge;

    private final DownloadLocationDialogCoordinator mLocationDialog;

    private Context mContext;
    private ModalDialogManager mModalDialogManager;
    private WindowAndroid mWindowAndroid;
    private long mTotalBytes;
    private @ConnectionType int mConnectionType = ConnectionType.CONNECTION_NONE;
    private @DownloadLocationDialogType int mLocationDialogType;
    private String mSuggestedPath;
    private PrefService mPrefService;

    public DownloadDialogBridge(
            long nativeDownloadDialogBridge, DownloadLocationDialogCoordinator locationDialog) {
        mNativeDownloadDialogBridge = nativeDownloadDialogBridge;
        mLocationDialog = locationDialog;
    }

    @CalledByNative
    private static DownloadDialogBridge create(long nativeDownloadDialogBridge) {
        DownloadLocationDialogCoordinator locationDialog = new DownloadLocationDialogCoordinator();
        DownloadDialogBridge bridge =
                new DownloadDialogBridge(nativeDownloadDialogBridge, locationDialog);
        locationDialog.initialize(bridge);
        return bridge;
    }

    @CalledByNative
    void destroy() {
        mNativeDownloadDialogBridge = 0;
        mLocationDialog.destroy();
    }

    private boolean downloadWithAdm(WindowAndroid windowAndroid, long totalBytes,
           String suggestedPath, String urlToDownload) {
        Activity activity = (Activity) windowAndroid.getActivity().get();
        // If the activity has gone away, just clean up the native pointer.
        if (activity == null) {
            return false;
        }

        boolean useAdmIfPossible = ContextUtils.getAppSharedPreferences().getBoolean("enable_external_download_manager", false);
        String activeDownloadManagerActivityName = ContextUtils.getAppSharedPreferences().getString("selected_external_download_manager_activity_name", "");
        String activeDownloadManagerPackageName = ContextUtils.getAppSharedPreferences().getString("selected_external_download_manager_package_name", "");

        if (useAdmIfPossible && !TextUtils.isEmpty(activeDownloadManagerPackageName) && !TextUtils.isEmpty(activeDownloadManagerActivityName) && activeDownloadManagerPackageName.equals("com.kiwibrowser.browser") != true
         && !TextUtils.isEmpty(urlToDownload) && (urlToDownload.toLowerCase(Locale.ROOT).startsWith("http:") || urlToDownload.toLowerCase(Locale.ROOT).startsWith("https:") || urlToDownload.toLowerCase(Locale.ROOT).startsWith("magnet:") || urlToDownload.toLowerCase(Locale.ROOT).startsWith("ftp:"))) {
            if (urlToDownload.toLowerCase(Locale.ROOT).contains(".googleusercontent.com/crx"))
                return false;
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(urlToDownload));
//            intent.addCategory("android.intent.category.BROWSABLE");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setClassName(activeDownloadManagerPackageName, activeDownloadManagerActivityName);

            intent.putExtra("android.intent.extra.TEXT", urlToDownload);

            // ADM
            intent.putExtra("com.android.extra.filename", new File(suggestedPath).getName());

            // MXPlayer
            intent.putExtra("title", new File(suggestedPath).getName());
            intent.putExtra("filename", new File(suggestedPath).getName());

            try {
                Log.i("Kiwi", "[Download] Starting activity intent [" + activeDownloadManagerPackageName + "/" + activeDownloadManagerActivityName + "]");
                activity.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException exception) {
                Log.i("Kiwi", "[Download] Starting activity intent: ActivityNotFoundException");
            }
        }
        return false;
    }

    @CalledByNative
    private void showDialog(
            WindowAndroid windowAndroid,
            long totalBytes,
            @ConnectionType int connectionType,
            @DownloadLocationDialogType int dialogType,
            String suggestedPath,
            boolean isIncognito) {
        mWindowAndroid = windowAndroid;
        Activity activity = windowAndroid.getActivity().get();
        if (activity == null) {
            onCancel();
            return;
        }
        if (downloadWithAdm(windowAndroid, totalBytes, suggestedPath, urlToDownload)) {
            onCancel();
            return;
        }


        DownloadDirectoryProvider.getInstance()
                .getAllDirectoriesOptions(
                        (dirs) -> {
                            ModalDialogManager modalDialogManager =
                                    ((ModalDialogManagerHolder) activity).getModalDialogManager();

                            // Suggests an alternative download location.
                            @DownloadLocationDialogType int suggestedDialogType = dialogType;
                            if (ChromeFeatureList.isEnabled(
                                            ChromeFeatureList.SMART_SUGGESTION_FOR_LARGE_DOWNLOADS)
                                    && DownloadDialogUtils.shouldSuggestDownloadLocation(
                                            dirs, getDownloadDefaultDirectory(), totalBytes)) {
                                suggestedDialogType =
                                        DownloadLocationDialogType.LOCATION_SUGGESTION;
                                DownloadLocationDialogMetrics.recordDownloadLocationSuggestionEvent(
                                        DownloadLocationSuggestionEvent.LOCATION_SUGGESTION_SHOWN);
                            }

                            showDialog(
                                    activity,
                                    modalDialogManager,
                                    DownloadSettings.getPrefService(),
                                    totalBytes,
                                    connectionType,
                                    suggestedDialogType,
                                    suggestedPath,
                                    isIncognito);
                        });
    }

    @VisibleForTesting
    void showDialog(
            Context context,
            ModalDialogManager modalDialogManager,
            PrefService prefService,
            long totalBytes,
            @ConnectionType int connectionType,
            @DownloadLocationDialogType int dialogType,
            String suggestedPath,
            boolean isIncognito) {
        mContext = context;
        mModalDialogManager = modalDialogManager;
        mPrefService = prefService;

        mTotalBytes = totalBytes;
        mConnectionType = connectionType;
        mLocationDialogType = dialogType;
        mSuggestedPath = suggestedPath;

        mLocationDialog.showDialog(
                mContext, mModalDialogManager, totalBytes, dialogType, suggestedPath, isIncognito);
    }

    private void onComplete() {
        if (mNativeDownloadDialogBridge == 0) return;

        DownloadDialogBridgeJni.get()
                .onComplete(mNativeDownloadDialogBridge, DownloadDialogBridge.this, mSuggestedPath);
    }

    private void onCancel() {
        if (mNativeDownloadDialogBridge == 0) return;
        DownloadDialogBridgeJni.get()
                .onCanceled(mNativeDownloadDialogBridge, DownloadDialogBridge.this);
        if (mWindowAndroid != null) {
            NewDownloadTab.closeExistingNewDownloadTab(mWindowAndroid);
            mWindowAndroid = null;
        }
    }

    // DownloadLocationDialogController implementation.
    @Override
    public void onDownloadLocationDialogComplete(String returnedPath) {
        mSuggestedPath = returnedPath;

        if (mLocationDialogType == DownloadLocationDialogType.LOCATION_SUGGESTION) {
            boolean isSelected = !mSuggestedPath.equals(getDownloadDefaultDirectory());
            DownloadLocationDialogMetrics.recordDownloadLocationSuggestionChoice(isSelected);
        }

        onComplete();
    }

    @Override
    public void onDownloadLocationDialogCanceled() {
        onCancel();
    }

    void setPrefServiceForTesting(PrefService prefService) {
        mPrefService = prefService;
    }

    /**
     * @return The stored download default directory.
     */
    public static String getDownloadDefaultDirectory() {
        return DownloadDialogBridgeJni.get().getDownloadDefaultDirectory();
    }

    /**
     * @param directory New directory to set as the download default directory.
     */
    public static void setDownloadAndSaveFileDefaultDirectory(String directory) {
        DownloadDialogBridgeJni.get().setDownloadAndSaveFileDefaultDirectory(directory);
    }

    /**
     * @return The status of prompt for download pref, defined by {@link DownloadPromptStatus}.
     */
    public static @DownloadPromptStatus int getPromptForDownloadAndroid() {
        return DownloadSettings.getPrefService().getInteger(Pref.PROMPT_FOR_DOWNLOAD_ANDROID);
    }

    /**
     * @param status New status to update the prompt for download preference.
     */
    public static void setPromptForDownloadAndroid(@DownloadPromptStatus int status) {
        DownloadSettings.getPrefService().setInteger(Pref.PROMPT_FOR_DOWNLOAD_ANDROID, status);
    }

    /**
     * @return The value for {@link Pref#PROMPT_FOR_DOWNLOAD}. This is currently only used by
     * enterprise policy.
     */
    public static boolean getPromptForDownloadPolicy() {
        return DownloadSettings.getPrefService().getBoolean(Pref.PROMPT_FOR_DOWNLOAD);
    }

    /**
     * @return whether to prompt the download location dialog is controlled by enterprise policy.
     */
    public static boolean isLocationDialogManaged() {
        return DownloadDialogBridgeJni.get().isLocationDialogManaged();
    }

    @NativeMethods
    public interface Natives {
        void onComplete(
                long nativeDownloadDialogBridge, DownloadDialogBridge caller, String returnedPath);

        void onCanceled(long nativeDownloadDialogBridge, DownloadDialogBridge caller);

        String getDownloadDefaultDirectory();

        void setDownloadAndSaveFileDefaultDirectory(String directory);

        boolean isLocationDialogManaged();
    }
}
