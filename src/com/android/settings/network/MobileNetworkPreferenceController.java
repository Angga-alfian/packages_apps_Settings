/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.settings.network;

import static android.os.UserHandle.myUserId;
import static android.os.UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserManager;
import android.provider.Settings;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import com.android.settings.core.PreferenceControllerMixin;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedPreference;
import com.android.settings.Utils;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.core.lifecycle.LifecycleObserver;
import com.android.settingslib.core.lifecycle.events.OnStart;
import com.android.settingslib.core.lifecycle.events.OnStop;

import java.util.List;


public class MobileNetworkPreferenceController extends AbstractPreferenceController
        implements PreferenceControllerMixin, LifecycleObserver, OnStart, OnStop {

    private static final String KEY_MOBILE_NETWORK_SETTINGS = "mobile_network_settings";

    private final boolean mIsSecondaryUser;
    private final TelephonyManager mTelephonyManager;
    private final UserManager mUserManager;
    private Preference mPreference;
    @VisibleForTesting
    PhoneStateListener mPhoneStateListener;
    private SubscriptionManager mSubscriptionManager;

    private BroadcastReceiver mAirplanModeChangedReceiver;

    private String mSummary;

    public MobileNetworkPreferenceController(Context context) {
        super(context);
        mUserManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mIsSecondaryUser = !mUserManager.isAdminUser();

        mAirplanModeChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateDisplayName();
                updateState(mPreference);
            }
        };
        mSubscriptionManager = SubscriptionManager.from(context);
    }

    @Override
    public boolean isAvailable() {
        return !isUserRestricted() && !Utils.isWifiOnly(mContext);
    }

    public boolean isUserRestricted() {
        return mIsSecondaryUser ||
                RestrictedLockUtils.hasBaseUserRestriction(
                        mContext,
                        DISALLOW_CONFIG_MOBILE_NETWORKS,
                        myUserId());
    }

    @Override
    public void displayPreference(PreferenceScreen screen) {
        super.displayPreference(screen);
        mPreference = screen.findPreference(getPreferenceKey());
    }

    @Override
    public String getPreferenceKey() {
        return KEY_MOBILE_NETWORK_SETTINGS;
    }

    @Override
    public void onStart() {
        if (mSubscriptionManager != null)
            mSubscriptionManager.addOnSubscriptionsChangedListener(mOnSubscriptionsChangeListener);
        if (isAvailable()) {
            if (mPhoneStateListener == null) {
                mPhoneStateListener = new PhoneStateListener() {
                    @Override
                    public void onServiceStateChanged(ServiceState serviceState) {
                        updateDisplayName();
                        updateState(mPreference);
                    }
                };
            }
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
        }
        if (mAirplanModeChangedReceiver != null) {
            mContext.registerReceiver(mAirplanModeChangedReceiver,
                new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED));
        }
    }

    private void updateDisplayName() {
        if (mPreference != null) {
            List<SubscriptionInfo> list = mSubscriptionManager.getActiveSubscriptionInfoList();
            if (list != null && !list.isEmpty()) {
                boolean useSeparator = false;
                StringBuilder builder = new StringBuilder();
                for (SubscriptionInfo subInfo : list) {
                    if (isSubscriptionInService(subInfo.getSubscriptionId())) {
                        if (useSeparator) builder.append(", ");
                        builder.append(mTelephonyManager.getNetworkOperatorName
                                (subInfo.getSubscriptionId()));
                        useSeparator = true;
                    }
                }
                mSummary = builder.toString();
            } else {
                mSummary = mTelephonyManager.getNetworkOperatorName();
            }
        }
    }

    private boolean isSubscriptionInService(int subId) {
        if (mTelephonyManager != null) {
            if (mTelephonyManager.getServiceStateForSubscriber(subId).getState()
                    == ServiceState.STATE_IN_SERVICE) {
                return true;
            }
        }
        return false;
    }

    private final SubscriptionManager.OnSubscriptionsChangedListener mOnSubscriptionsChangeListener
            = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override
        public void onSubscriptionsChanged() {
             updateDisplayName();
             updateState(mPreference);
        }
    };

    @Override
    public void onStop() {
        if (mPhoneStateListener != null) {
            mTelephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
        }
        mSubscriptionManager
                .removeOnSubscriptionsChangedListener(mOnSubscriptionsChangeListener);
        if (mAirplanModeChangedReceiver != null) {
            mContext.unregisterReceiver(mAirplanModeChangedReceiver);
        }
    }

    @Override
    public void updateState(Preference preference) {
        super.updateState(preference);

        if (preference instanceof RestrictedPreference &&
            ((RestrictedPreference) preference).isDisabledByAdmin()) {
                return;
        }
        preference.setEnabled(Settings.Global.getInt(
            mContext.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) == 0);
    }

    @Override
    public boolean handlePreferenceTreeClick(Preference preference) {
        if (KEY_MOBILE_NETWORK_SETTINGS.equals(preference.getKey()) &&
                Utils.isNetworkSettingsApkAvailable()) {
            final Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setComponent(new ComponentName("com.qualcomm.qti.networksetting",
                    "com.qualcomm.qti.networksetting.MobileNetworkSettings"));
            mContext.startActivity(intent);
            return true;
        }
        return false;
    }

    public CharSequence getSummary() {
        return mSummary;
    }
}
