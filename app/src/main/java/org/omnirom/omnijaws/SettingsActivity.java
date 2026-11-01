/*
 * Copyright (C) 2025 IchthysOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.omnirom.omnijaws;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.view.View;
import android.view.WindowInsets;

/**
 * Minimal weather settings for OmniJaws. The Compose SettingsActivity was
 * dropped in the headless strip; this framework-preferences replacement lets
 * the user enable weather and pick a provider/units/interval. Location is
 * the shared place from Settings > Guard (IchthysOS), else device-based
 * (GPS/network). Uses the default SharedPreferences that
 * {@link Config} reads. Any change kicks a fresh fetch so the lockscreen /
 * status-bar weather (which read this app's provider) light up.
 */
public class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new WeatherPrefsFragment())
                .commit();
        // A15+ apps are edge-to-edge: without this the preference list is drawn UNDER the
        // status bar (title collides with the clock). Pad the content for the system bars.
        final View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    public static class WeatherPrefsFragment extends PreferenceFragment
            implements SharedPreferences.OnSharedPreferenceChangeListener {

        private static final String KEY_LOCATION_INFO = "location_info";

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.weather_settings);
        }

        @Override
        public void onResume() {
            super.onResume();
            getPreferenceManager().getSharedPreferences()
                    .registerOnSharedPreferenceChangeListener(this);
            // IchthysOS: say which place the weather is for (Guard's shared place, else this
            // device's location); it is changed in Settings > Guard > Rules > Shared values.
            final Preference location = findPreference(KEY_LOCATION_INFO);
            if (location != null) {
                final String name = Config.getSharedManualLocationName(getActivity());
                location.setSummary(name != null
                        ? getString(R.string.weather_location_shared, name)
                        : getString(R.string.weather_location_device));
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            getPreferenceManager().getSharedPreferences()
                    .unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (getActivity() == null) return;
            if (Config.PREF_KEY_ENABLE.equals(key)) {
                if (Config.isEnabled(getActivity())) {
                    WeatherUpdateService.scheduleUpdatePeriodic(getActivity());
                    WeatherUpdateService.scheduleUpdateNow(getActivity());
                }
            } else if (Config.isEnabled(getActivity())) {
                if (Config.PREF_KEY_ICON_PACK.equals(key)) {
                    // Icon pack: no network refetch needed. Poke SystemUI's OmniJawsClient with
                    // the weather-update broadcast it listens for so it re-queries and reloads
                    // the icon package live (updateSettings() -> loadCustomIconPackage()).
                    // Plain sendBroadcast (current user) — OmniJaws runs as a normal app uid and
                    // sendBroadcastAsUser(ALL) would throw (needs INTERACT_ACROSS_USERS) and crash.
                    try {
                        getActivity().sendBroadcast(
                                new Intent("org.omnirom.omnijaws.WEATHER_UPDATE")
                                        .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
                    } catch (Exception e) {
                        // never let a settings change crash the app
                    }
                } else {
                    // provider / units / interval change requires a fresh fetch.
                    WeatherUpdateService.scheduleUpdatePeriodic(getActivity());
                    WeatherUpdateService.scheduleUpdateNow(getActivity());
                }
            }
            // Also let content observers re-read the settings immediately.
            getActivity().getContentResolver().notifyChange(
                    Uri.parse("content://" + WeatherContentProvider.AUTHORITY + "/settings"),
                    null);
        }
    }
}
