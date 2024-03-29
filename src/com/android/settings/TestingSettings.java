/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.settings;

import com.android.internal.telephony.PhoneConstants;
import android.os.Bundle;
import android.os.UserManager;
import androidx.preference.PreferenceScreen;
import android.telephony.TelephonyManager;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

public class TestingSettings extends SettingsPreferenceFragment {
    int sNumPhones = TelephonyManager.getDefault().getPhoneCount();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        addPreferencesFromResource(R.xml.testing_settings);

        final UserManager um = UserManager.get(getContext());
        if (!um.isAdminUser()) {
            PreferenceScreen preferenceScreen = (PreferenceScreen)
                    findPreference("radio_info_settings");
            getPreferenceScreen().removePreference(preferenceScreen);
        }

        if (PhoneConstants.MAX_PHONE_COUNT_DUAL_SIM == sNumPhones) {
            PreferenceScreen preferenceScreen = (PreferenceScreen)
                    findPreference("radio_info_settings");
            getPreferenceScreen().removePreference(preferenceScreen);
        } else if (PhoneConstants.MAX_PHONE_COUNT_SINGLE_SIM == sNumPhones) {
            PreferenceScreen preferenceScreen1 = (PreferenceScreen)
                    findPreference("radio_info1_settings");
            getPreferenceScreen().removePreference(preferenceScreen1);

            PreferenceScreen preferenceScreen2 = (PreferenceScreen)
                    findPreference("radio_info2_settings");
            getPreferenceScreen().removePreference(preferenceScreen2);
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.TESTING;
    }
}
