/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.settings.datausage;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.NetworkTemplate;
import android.os.Bundle;
import android.provider.SearchIndexableResource;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.SubscriptionPlan;
import android.text.BidiFormatter;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.text.style.RelativeSizeSpan;
import android.util.Log;
import android.view.MenuItem;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settings.R;
import com.android.settings.Utils;
import com.android.settings.dashboard.SummaryLoader;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.search.Indexable;
import com.android.settingslib.NetworkPolicyEditor;
import com.android.settingslib.core.AbstractPreferenceController;
import com.android.settingslib.net.DataUsageController;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings preference fragment that displays data usage summary.
 */
public class DataUsageSummary extends DataUsageBaseFragment implements Indexable,
        DataUsageEditController {

    private static final String TAG = "DataUsageSummary";

    static final boolean LOGD = false;

    public static final String KEY_RESTRICT_BACKGROUND = "restrict_background";

    private static final String KEY_STATUS_HEADER = "status_header";

    // Mobile data keys
    public static final String KEY_MOBILE_USAGE_TITLE = "mobile_category";
    public static final String KEY_MOBILE_DATA_USAGE_TOGGLE = "data_usage_enable";
    public static final String KEY_MOBILE_DATA_USAGE = "cellular_data_usage";
    public static final String KEY_MOBILE_BILLING_CYCLE = "billing_preference";

    // Wifi keys
    public static final String KEY_WIFI_USAGE_TITLE = "wifi_category";
    public static final String KEY_WIFI_DATA_USAGE = "wifi_data_usage";

    private DataUsageSummaryPreference mSummaryPreference;
    private DataUsageSummaryPreferenceController mSummaryController;
    private NetworkTemplate mDefaultTemplate;

    @Override
    public int getHelpResource() {
        return R.string.help_url_data_usage;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Context context = getContext();

        boolean hasMobileData = DataUsageUtils.hasMobileData(context);

        int defaultSubId = DataUsageUtils.getDefaultSubscriptionId(context);
        if (defaultSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            hasMobileData = false;
        }
        mDefaultTemplate = DataUsageUtils.getDefaultTemplate(context, defaultSubId);
        mSummaryPreference = (DataUsageSummaryPreference) findPreference(KEY_STATUS_HEADER);

        if (!hasMobileData || !isAdmin()) {
            removePreference(KEY_RESTRICT_BACKGROUND);
        }
        boolean hasWifiRadio = DataUsageUtils.hasWifiRadio(context);
        if (hasMobileData) {
            List<SubscriptionInfo> subscriptions =
                    services.mSubscriptionManager.getActiveSubscriptionInfoList();
            if (subscriptions == null || subscriptions.size() == 0) {
                addMobileSection(defaultSubId);
            }
            for (int i = 0; subscriptions != null && i < subscriptions.size(); i++) {
                SubscriptionInfo subInfo = subscriptions.get(i);
                if (subscriptions.size() > 1) {
                    addMobileSection(subInfo.getSubscriptionId(), subInfo);
                } else {
                    addMobileSection(subInfo.getSubscriptionId());
                }
            }
            if (DataUsageUtils.hasSim(context) && hasWifiRadio) {
                // If the device has a SIM installed, the data usage section shows usage for mobile,
                // and the WiFi section is added if there is a WiFi radio - legacy behavior.
                addWifiSection();
            }
            // Do not add the WiFi section if either there is no WiFi radio (obviously) or if no
            // SIM is installed. In the latter case the data usage section will show WiFi usage and
            // there should be no explicit WiFi section added.
        } else if (hasWifiRadio) {
            addWifiSection();
        }
        if (DataUsageUtils.hasEthernet(context)) {
            addEthernetSection();
        }
        setHasOptionsMenu(true);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.data_usage_menu_cellular_networks: {
                final Intent intent = new Intent(Intent.ACTION_MAIN);
                if (Utils.isNetworkSettingsApkAvailable()) {
                    // prepare intent to start qti MobileNetworkSettings activity
                    intent.setComponent(new ComponentName("com.qualcomm.qti.networksetting",
                            "com.qualcomm.qti.networksetting.MobileNetworkSettings"));
                } else {
                    // vendor MobileNetworkSettings not available, launch the default activity
                    Log.d(TAG, "vendor MobileNetworkSettings is not available");
                    intent.setComponent(new ComponentName("com.android.phone",
                            "com.android.phone.MobileNetworkSettings"));
                }
                startActivity(intent);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (preference == findPreference(KEY_STATUS_HEADER)) {
            BillingCycleSettings.BytesEditorFragment.show(this, false);
            return false;
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.data_usage;
    }

    @Override
    protected String getLogTag() {
        return TAG;
    }

    @Override
    protected List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        final Activity activity = getActivity();
        final ArrayList<AbstractPreferenceController> controllers = new ArrayList<>();
        mSummaryController =
                new DataUsageSummaryPreferenceController(activity, getLifecycle(), this);
        controllers.add(mSummaryController);
        getLifecycle().addObserver(mSummaryController);
        return controllers;
    }

    @VisibleForTesting
    void addMobileSection(int subId) {
        addMobileSection(subId, null);
    }

    private void addMobileSection(int subId, SubscriptionInfo subInfo) {
        TemplatePreferenceCategory category = (TemplatePreferenceCategory)
                inflatePreferences(R.xml.data_usage_cellular);
        category.setTemplate(getNetworkTemplate(subId), subId, services);
        category.pushTemplates(services);
        if (subInfo != null && !TextUtils.isEmpty(subInfo.getDisplayName())) {
            Preference title  = category.findPreference(KEY_MOBILE_USAGE_TITLE);
            title.setTitle(subInfo.getDisplayName());
        }
    }

    @VisibleForTesting
    void addWifiSection() {
        TemplatePreferenceCategory category = (TemplatePreferenceCategory)
                inflatePreferences(R.xml.data_usage_wifi);
        category.setTemplate(NetworkTemplate.buildTemplateWifiWildcard(), 0, services);
    }

    private void addEthernetSection() {
        TemplatePreferenceCategory category = (TemplatePreferenceCategory)
                inflatePreferences(R.xml.data_usage_ethernet);
        category.setTemplate(NetworkTemplate.buildTemplateEthernet(), 0, services);
    }

    private Preference inflatePreferences(int resId) {
        PreferenceScreen rootPreferences = getPreferenceManager().inflateFromResource(
                getPrefContext(), resId, null);
        Preference pref = rootPreferences.getPreference(0);
        rootPreferences.removeAll();

        PreferenceScreen screen = getPreferenceScreen();
        pref.setOrder(screen.getPreferenceCount());
        screen.addPreference(pref);

        return pref;
    }

    private NetworkTemplate getNetworkTemplate(int subscriptionId) {
        NetworkTemplate mobileAll = NetworkTemplate.buildTemplateMobileAll(
                services.mTelephonyManager.getSubscriberId(subscriptionId));
        return NetworkTemplate.normalize(mobileAll,
                services.mTelephonyManager.getMergedSubscriberIds());
    }

    @Override
    public void onResume() {
        super.onResume();
        updateState();
    }

    @VisibleForTesting
    static CharSequence formatUsage(Context context, String template, long usageLevel) {
        final float LARGER_SIZE = 1.25f * 1.25f;  // (1/0.8)^2
        final float SMALLER_SIZE = 1.0f / LARGER_SIZE;  // 0.8^2
        return formatUsage(context, template, usageLevel, LARGER_SIZE, SMALLER_SIZE);
    }

    static CharSequence formatUsage(Context context, String template, long usageLevel,
                                    float larger, float smaller) {
        final int FLAGS = Spannable.SPAN_INCLUSIVE_INCLUSIVE;

        final Formatter.BytesResult usedResult = Formatter.formatBytes(context.getResources(),
                usageLevel, Formatter.FLAG_CALCULATE_ROUNDED | Formatter.FLAG_IEC_UNITS);
        final SpannableString enlargedValue = new SpannableString(usedResult.value);
        enlargedValue.setSpan(new RelativeSizeSpan(larger), 0, enlargedValue.length(), FLAGS);

        final SpannableString amountTemplate = new SpannableString(
                context.getString(com.android.internal.R.string.fileSizeSuffix)
                .replace("%1$s", "^1").replace("%2$s", "^2"));
        final CharSequence formattedUsage = TextUtils.expandTemplate(amountTemplate,
                enlargedValue, usedResult.units);

        final SpannableString fullTemplate = new SpannableString(template);
        fullTemplate.setSpan(new RelativeSizeSpan(smaller), 0, fullTemplate.length(), FLAGS);
        return TextUtils.expandTemplate(fullTemplate,
                BidiFormatter.getInstance().unicodeWrap(formattedUsage.toString()));
    }

    private void updateState() {
        PreferenceScreen screen = getPreferenceScreen();
        for (int i = 1; i < screen.getPreferenceCount(); i++) {
          Preference currentPreference = screen.getPreference(i);
          if (currentPreference instanceof TemplatePreferenceCategory) {
            ((TemplatePreferenceCategory) currentPreference).pushTemplates(services);
          }
        }
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.DATA_USAGE_SUMMARY;
    }

    @Override
    public NetworkPolicyEditor getNetworkPolicyEditor() {
        return services.mPolicyEditor;
    }

    @Override
    public NetworkTemplate getNetworkTemplate() {
        return mDefaultTemplate;
    }

    @Override
    public void updateDataUsage() {
        updateState();
        mSummaryController.updateState(mSummaryPreference);
    }

    private static class SummaryProvider
            implements SummaryLoader.SummaryProvider {

        private final Activity mActivity;
        private final SummaryLoader mSummaryLoader;
        private final DataUsageController mDataController;

        public SummaryProvider(Activity activity, SummaryLoader summaryLoader) {
            mActivity = activity;
            mSummaryLoader = summaryLoader;
            mDataController = new DataUsageController(activity);
        }

        @Override
        public void setListening(boolean listening) {
            if (listening) {
                if (DataUsageUtils.hasSim(mActivity)) {
                    mSummaryLoader.setSummary(this,
                            mActivity.getString(R.string.data_usage_summary_format,
                                    formatUsedData()));
                } else {
                    final DataUsageController.DataUsageInfo info =
                            mDataController
                                    .getDataUsageInfo(NetworkTemplate.buildTemplateWifiWildcard());

                    if (info == null) {
                        mSummaryLoader.setSummary(this, null);
                    } else {
                        final CharSequence wifiFormat = mActivity
                                .getText(R.string.data_usage_wifi_format);
                        final CharSequence sizeText =
                                DataUsageUtils.formatDataUsage(mActivity, info.usageLevel);
                        mSummaryLoader.setSummary(this,
                                TextUtils.expandTemplate(wifiFormat, sizeText));
                    }
                }
            }
        }

        private CharSequence formatUsedData() {
            SubscriptionManager subscriptionManager = (SubscriptionManager) mActivity
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            int defaultSubId = subscriptionManager.getDefaultSubscriptionId();
            if (defaultSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                return formatFallbackData();
            }
            SubscriptionPlan dfltPlan = DataUsageSummaryPreferenceController
                    .getPrimaryPlan(subscriptionManager, defaultSubId);
            if (dfltPlan == null) {
                return formatFallbackData();
            }
            if (DataUsageSummaryPreferenceController.unlimited(dfltPlan.getDataLimitBytes())) {
                return DataUsageUtils.formatDataUsage(mActivity, dfltPlan.getDataUsageBytes());
            } else {
                return Utils.formatPercentage(dfltPlan.getDataUsageBytes(),
                    dfltPlan.getDataLimitBytes());
            }
        }

        private CharSequence formatFallbackData() {
            DataUsageController.DataUsageInfo info = mDataController.getDataUsageInfo();
            if (info == null) {
                return DataUsageUtils.formatDataUsage(mActivity, 0);
            } else if (info.limitLevel <= 0) {
                return DataUsageUtils.formatDataUsage(mActivity, info.usageLevel);
            } else {
                return Utils.formatPercentage(info.usageLevel, info.limitLevel);
            }
        }
    }

    public static final SummaryLoader.SummaryProviderFactory SUMMARY_PROVIDER_FACTORY
        = SummaryProvider::new;

    /**
     * For search
     */
    public static final Indexable.SearchIndexProvider SEARCH_INDEX_DATA_PROVIDER =
        new BaseSearchIndexProvider() {

            @Override
            public List<SearchIndexableResource> getXmlResourcesToIndex(Context context,
                    boolean enabled) {
                List<SearchIndexableResource> resources = new ArrayList<>();
                SearchIndexableResource resource = new SearchIndexableResource(context);
                resource.xmlResId = R.xml.data_usage;
                resources.add(resource);

                resource = new SearchIndexableResource(context);
                resource.xmlResId = R.xml.data_usage_cellular;
                resources.add(resource);

                resource = new SearchIndexableResource(context);
                resource.xmlResId = R.xml.data_usage_wifi;
                resources.add(resource);

                return resources;
            }

            @Override
            public List<String> getNonIndexableKeys(Context context) {
                List<String> keys = super.getNonIndexableKeys(context);

                if (!DataUsageUtils.hasMobileData(context)) {
                    keys.add(KEY_MOBILE_USAGE_TITLE);
                    keys.add(KEY_MOBILE_DATA_USAGE_TOGGLE);
                    keys.add(KEY_MOBILE_DATA_USAGE);
                    keys.add(KEY_MOBILE_BILLING_CYCLE);
                }

                if (!DataUsageUtils.hasWifiRadio(context)) {
                    keys.add(KEY_WIFI_DATA_USAGE);
                }

                // This title is named Wifi, and will confuse users.
                keys.add(KEY_WIFI_USAGE_TITLE);

                return keys;
            }
        };
}
