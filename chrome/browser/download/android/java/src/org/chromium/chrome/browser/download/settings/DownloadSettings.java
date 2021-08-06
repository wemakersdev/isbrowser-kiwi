// Copyright 2017 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chrome.browser.download.settings;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

import org.chromium.chrome.browser.download.DownloadDialogBridge;
import org.chromium.chrome.browser.download.DownloadPromptStatus;
import org.chromium.chrome.browser.download.MimeUtils;
import org.chromium.chrome.browser.download.R;
import org.chromium.chrome.browser.profiles.Profile;
import org.chromium.chrome.browser.settings.ChromeBaseSettingsFragment;
import org.chromium.chrome.browser.settings.ChromeManagedPreferenceDelegate;
import org.chromium.components.browser_ui.settings.ChromeSwitchPreference;
import org.chromium.components.browser_ui.settings.ManagedPreferenceDelegate;
import org.chromium.components.browser_ui.settings.SettingsUtils;
import org.chromium.components.prefs.PrefService;
import org.chromium.components.user_prefs.UserPrefs;

/** Fragment containing Download settings. */
public class DownloadSettings extends ChromeBaseSettingsFragment
        implements Preference.OnPreferenceChangeListener {
    public static final String PREF_LOCATION_CHANGE = "location_change";
    public static final String PREF_LOCATION_PROMPT_ENABLED = "location_prompt_enabled";
    public static final String PREF_AUTO_OPEN_PDF_ENABLED = "auto_open_pdf_enabled";

    private DownloadLocationPreference mLocationChangePref;
    private ChromeSwitchPreference mLocationPromptEnabledPref;
    private ManagedPreferenceDelegate mLocationPromptEnabledPrefDelegate;
    private ChromeSwitchPreference mAutoOpenPdfEnabledPref;

    private ChromeBaseCheckBoxPreference mExternalDownloadManager;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, String s) {
        getActivity().setTitle(R.string.menu_downloads);
        SettingsUtils.addPreferencesFromResource(this, R.xml.download_preferences);

        mLocationPromptEnabledPref =
                (ChromeSwitchPreference) findPreference(PREF_LOCATION_PROMPT_ENABLED);
        mLocationPromptEnabledPref.setOnPreferenceChangeListener(this);
        mLocationPromptEnabledPrefDelegate =
                new ChromeManagedPreferenceDelegate(getProfile()) {
                    @Override
                    public boolean isPreferenceControlledByPolicy(Preference preference) {
                        return DownloadDialogBridge.isLocationDialogManaged();
                    }
                };
        mLocationPromptEnabledPref.setManagedPreferenceDelegate(mLocationPromptEnabledPrefDelegate);
        mLocationChangePref = (DownloadLocationPreference) findPreference(PREF_LOCATION_CHANGE);
        mAutoOpenPdfEnabledPref =
                (ChromeSwitchPreference) findPreference(PREF_AUTO_OPEN_PDF_ENABLED);
        mAutoOpenPdfEnabledPref.setOnPreferenceChangeListener(this);
        String summary =
                (MimeUtils.getPdfIntentHandlers().size() == 1)
                        ? getActivity()
                                .getString(
                                        R.string.auto_open_pdf_enabled_with_app_description,
                                        MimeUtils.getDefaultPdfViewerName())
                        : getActivity().getString(R.string.auto_open_pdf_enabled_description);
        mAutoOpenPdfEnabledPref.setSummaryOn(summary);
        if (!ChromeFeatureList.isEnabled(ChromeFeatureList.OPEN_DOWNLOAD_DIALOG)) {
            getPreferenceScreen().removePreference(findPreference(PREF_AUTO_OPEN_PDF_ENABLED));
        }
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof DownloadLocationPreference) {
            DownloadLocationPreferenceDialog dialogFragment =
                    DownloadLocationPreferenceDialog.newInstance(
                            (DownloadLocationPreference) preference);
            dialogFragment.setTargetFragment(this, 0);
            dialogFragment.show(getFragmentManager(), DownloadLocationPreferenceDialog.TAG);
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDownloadSettings();
    }

    private void updateDownloadSettings() {
        mLocationChangePref.updateSummary();

        if (DownloadDialogBridge.isLocationDialogManaged()) {
            // Location prompt can be controlled by the enterprise policy.
            mLocationPromptEnabledPref.setChecked(
                    DownloadDialogBridge.getPromptForDownloadPolicy());
        } else {
            // Location prompt is marked enabled if the prompt status is not DONT_SHOW.
            boolean isLocationPromptEnabled =
                    DownloadDialogBridge.getPromptForDownloadAndroid()
                            != DownloadPromptStatus.DONT_SHOW;
            mLocationPromptEnabledPref.setChecked(isLocationPromptEnabled);
            mLocationPromptEnabledPref.setEnabled(true);
        }
        if (ChromeFeatureList.isEnabled(ChromeFeatureList.OPEN_DOWNLOAD_DIALOG)) {
            mAutoOpenPdfEnabledPref.setChecked(
                    getPrefService().getBoolean(Pref.AUTO_OPEN_PDF_ENABLED));
            mAutoOpenPdfEnabledPref.setEnabled(true);
        }
    }

    // Preference.OnPreferenceChangeListener implementation.
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (PREF_LOCATION_PROMPT_ENABLED.equals(preference.getKey())) {
            if ((boolean) newValue) {
                // Only update if the download location dialog has been shown before.
                if (DownloadDialogBridge.getPromptForDownloadAndroid()
                        != DownloadPromptStatus.SHOW_INITIAL) {
                    DownloadDialogBridge.setPromptForDownloadAndroid(
                            DownloadPromptStatus.SHOW_PREFERENCE);
                }
            } else {
                DownloadDialogBridge.setPromptForDownloadAndroid(DownloadPromptStatus.DONT_SHOW);
            }
        } else if (PREF_AUTO_OPEN_PDF_ENABLED.equals(preference.getKey())) {
            getPrefService().setBoolean(Pref.AUTO_OPEN_PDF_ENABLED, (boolean) newValue);
        }
        else if ("enable_external_download_manager".equals(preference.getKey())) {
            SharedPreferences.Editor sharedPreferencesEditor = ContextUtils.getAppSharedPreferences().edit();
            sharedPreferencesEditor.putBoolean("enable_external_download_manager", (boolean)newValue);
            sharedPreferencesEditor.apply();
            if ((boolean)newValue == true) {
                    List<Intent> targetedShareIntents = new ArrayList<Intent>();
                    Intent shareIntent = new Intent(android.content.Intent.ACTION_VIEW, Uri.parse("http://test.com/file.rar"));
                    // Set title and text to share when the user selects an option.
                    shareIntent.putExtra(Intent.EXTRA_TEXT, "http://test.com/file.rar");
                    List<ResolveInfo> resInfo = getActivity().getPackageManager().queryIntentActivities(shareIntent, 0);
                    if (!resInfo.isEmpty()) {
                        for (ResolveInfo info : resInfo) {
                            if (!"com.kiwibrowser.browser".equalsIgnoreCase(info.activityInfo.packageName)) {
                                Intent targetedShare = new Intent(android.content.Intent.ACTION_VIEW);
                                targetedShare.setPackage(info.activityInfo.packageName.toLowerCase(Locale.ROOT));
                                targetedShareIntents.add(targetedShare);
                            }
                        }
                        // Then show the ACTION_PICK_ACTIVITY to let the user select it
                        Intent intentPick = new Intent();
                        intentPick.setAction(Intent.ACTION_PICK_ACTIVITY);
                        // Set the title of the dialog
                        intentPick.putExtra(Intent.EXTRA_TITLE, "Download manager");
                        intentPick.putExtra(Intent.EXTRA_INTENT, shareIntent);
                        intentPick.putExtra(Intent.EXTRA_INITIAL_INTENTS, targetedShareIntents.toArray());
                        // Call StartActivityForResult so we can get the app name selected by the user
                        this.startActivityForResult(intentPick, /* REQUEST_CODE_MY_PICK */ 4242);
                    }
            }
        }
        return true;
    }

    public ManagedPreferenceDelegate getLocationPromptEnabledPrefDelegateForTesting() {
        return mLocationPromptEnabledPrefDelegate;
    }

    /**
     * @return pref service from last used regular profile.
     */
    public static PrefService getPrefService() {
        return UserPrefs.get(Profile.getLastUsedRegularProfile());
    }
}
