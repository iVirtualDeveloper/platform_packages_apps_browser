/*
 * Copyright (C) 2010 The Android Open Source Project
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


package com.android.browser;

import com.android.browser.search.SearchEngine;
import com.android.common.Search;
import com.android.common.speech.LoggingEvents;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Browser;
import android.provider.MediaStore;
import android.speech.RecognizerResultsIntent;
import android.text.TextUtils;
import android.util.Patterns;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Handle all browser related intents
 */
public class IntentHandler {

    // "source" parameter for Google search suggested by the browser
    final static String GOOGLE_SEARCH_SOURCE_SUGGEST = "browser-suggest";
    // "source" parameter for Google search from unknown source
    final static String GOOGLE_SEARCH_SOURCE_UNKNOWN = "unknown";

    /* package */ static final UrlData EMPTY_URL_DATA = new UrlData(null);

    private Activity mActivity;
    private Controller mController;
    private TabControl mTabControl;
    private BrowserSettings mSettings;

    public IntentHandler(Activity browser, Controller controller) {
        mActivity = browser;
        mController = controller;
        mTabControl = mController.getTabControl();
        mSettings = controller.getSettings();
    }

    void onNewIntent(Intent intent) {
        Tab current = mTabControl.getCurrentTab();
        // When a tab is closed on exit, the current tab index is set to -1.
        // Reset before proceed as Browser requires the current tab to be set.
        if (current == null) {
            // Try to reset the tab in case the index was incorrect.
            current = mTabControl.getTab(0);
            if (current == null) {
                // No tabs at all so just ignore this intent.
                return;
            }
            mController.setActiveTab(current);
        }
        final String action = intent.getAction();
        final int flags = intent.getFlags();
        if (Intent.ACTION_MAIN.equals(action) ||
                (flags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            // just resume the browser
            return;
        }
        if (BrowserActivity.ACTION_SHOW_BOOKMARKS.equals(action)) {
            mController.bookmarksOrHistoryPicker(false);
            return;
        }
        mController.removeComboView();

        // In case the SearchDialog is open.
        ((SearchManager) mActivity.getSystemService(Context.SEARCH_SERVICE))
                .stopSearch();
        boolean activateVoiceSearch = RecognizerResultsIntent
                .ACTION_VOICE_SEARCH_RESULTS.equals(action);
        if (Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_SEARCH.equals(action)
                || MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(action)
                || Intent.ACTION_WEB_SEARCH.equals(action)
                || activateVoiceSearch) {
            if (current.isInVoiceSearchMode()) {
                String title = current.getVoiceDisplayTitle();
                if (title != null && title.equals(intent.getStringExtra(
                        SearchManager.QUERY))) {
                    // The user submitted the same search as the last voice
                    // search, so do nothing.
                    return;
                }
                if (Intent.ACTION_SEARCH.equals(action)
                        && current.voiceSearchSourceIsGoogle()) {
                    Intent logIntent = new Intent(
                            LoggingEvents.ACTION_LOG_EVENT);
                    logIntent.putExtra(LoggingEvents.EXTRA_EVENT,
                            LoggingEvents.VoiceSearch.QUERY_UPDATED);
                    logIntent.putExtra(
                            LoggingEvents.VoiceSearch.EXTRA_QUERY_UPDATED_VALUE,
                            intent.getDataString());
                    mActivity.sendBroadcast(logIntent);
                    // Note, onPageStarted will revert the voice title bar
                    // When http://b/issue?id=2379215 is fixed, we should update
                    // the title bar here.
                }
            }
            // If this was a search request (e.g. search query directly typed into the address bar),
            // pass it on to the default web search provider.
            if (handleWebSearchIntent(mActivity, mController, intent)) {
                return;
            }

            UrlData urlData = getUrlDataFromIntent(intent);
            if (urlData.isEmpty()) {
                urlData = new UrlData(mSettings.getHomePage());
            }

            if (intent.getBooleanExtra(Browser.EXTRA_CREATE_NEW_TAB, false)) {
                mController.openTabAndShow(mTabControl.getCurrentTab(), urlData, false, null);
                return;
            }
            final String appId = intent
                    .getStringExtra(Browser.EXTRA_APPLICATION_ID);
            if (!TextUtils.isEmpty(urlData.mUrl) &&
                    urlData.mUrl.startsWith("javascript:")) {
                // Always open javascript: URIs in new tabs
                mController.openTabAndShow(null, urlData, true, appId);
                return;
            }
            if ((Intent.ACTION_VIEW.equals(action)
                    // If a voice search has no appId, it means that it came
                    // from the browser.  In that case, reuse the current tab.
                    || (activateVoiceSearch && appId != null))
                    && !mActivity.getPackageName().equals(appId)
                    && (flags & Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) != 0) {
                if (activateVoiceSearch) {
                    Tab appTab = mTabControl.getTabFromId(appId);
                    if (appTab != null) {
                        mController.reuseTab(appTab, appId, urlData);
                        return;
                    } else {
                        mController.openTabAndShow(null, urlData, false, appId);
                    }
                } else {
                    // No matching application tab, try to find a regular tab
                    // with a matching url.
                    Tab appTab = mTabControl.findUnusedTabWithUrl(urlData.mUrl);
                    if (appTab != null) {
                        if (current != appTab) {
                            mController.switchToTab(mTabControl.getTabIndex(appTab));
                        }
                        // Otherwise, we are already viewing the correct tab.
                    } else {
                        // if FLAG_ACTIVITY_BROUGHT_TO_FRONT flag is on, the url
                        // will be opened in a new tab unless we have reached
                        // MAX_TABS. Then the url will be opened in the current
                        // tab. If a new tab is created, it will have "true" for
                        // exit on close.
                        mController.openTabAndShow(null, urlData, false, appId);
                    }
                }
            } else {
                if (!urlData.isEmpty()
                        && urlData.mUrl.startsWith("about:debug")) {
                    if ("about:debug.dom".equals(urlData.mUrl)) {
                        current.getWebView().dumpDomTree(false);
                    } else if ("about:debug.dom.file".equals(urlData.mUrl)) {
                        current.getWebView().dumpDomTree(true);
                    } else if ("about:debug.render".equals(urlData.mUrl)) {
                        current.getWebView().dumpRenderTree(false);
                    } else if ("about:debug.render.file".equals(urlData.mUrl)) {
                        current.getWebView().dumpRenderTree(true);
                    } else if ("about:debug.display".equals(urlData.mUrl)) {
                        current.getWebView().dumpDisplayTree();
                    } else if ("about:debug.nav".equals(urlData.mUrl)) {
                        current.getWebView().debugDump();
                    } else {
                        mSettings.toggleDebugSettings(mActivity);
                    }
                    return;
                }
                // Get rid of the subwindow if it exists
                mController.dismissSubWindow(current);
                // If the current Tab is being used as an application tab,
                // remove the association, since the new Intent means that it is
                // no longer associated with that application.
                current.setAppId(null);
                mController.loadUrlDataIn(current, urlData);
            }
        }
    }

    protected UrlData getUrlDataFromIntent(Intent intent) {
        String url = "";
        Map<String, String> headers = null;
        if (intent != null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) {
            final String action = intent.getAction();
            if (Intent.ACTION_VIEW.equals(action)) {
                url = UrlUtils.smartUrlFilter(intent.getData());
                if (url != null && url.startsWith("http")) {
                    final Bundle pairs = intent
                            .getBundleExtra(Browser.EXTRA_HEADERS);
                    if (pairs != null && !pairs.isEmpty()) {
                        Iterator<String> iter = pairs.keySet().iterator();
                        headers = new HashMap<String, String>();
                        while (iter.hasNext()) {
                            String key = iter.next();
                            headers.put(key, pairs.getString(key));
                        }
                    }

                    // AppId will be set to the Browser for Search Bar initiated searches
                    final String appId = intent.getStringExtra(Browser.EXTRA_APPLICATION_ID);
                    if (mActivity.getPackageName().equals(appId)) {
                        String rlz = mSettings.getRlzValue();
                        Uri uri = Uri.parse(url);
                        if (!rlz.isEmpty() && needsRlz(uri)) {
                            Uri rlzUri = addRlzParameter(uri, rlz);
                            url = rlzUri.toString();
                        }
                    }
                }
            } else if (Intent.ACTION_SEARCH.equals(action)
                    || MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(action)
                    || Intent.ACTION_WEB_SEARCH.equals(action)) {
                url = intent.getStringExtra(SearchManager.QUERY);
                if (url != null) {
                    // In general, we shouldn't modify URL from Intent.
                    // But currently, we get the user-typed URL from search box as well.
                    url = UrlUtils.fixUrl(url);
                    url = UrlUtils.smartUrlFilter(url);
                    String searchSource = "&source=android-" + GOOGLE_SEARCH_SOURCE_SUGGEST + "&";
                    if (url.contains(searchSource)) {
                        String source = null;
                        final Bundle appData = intent.getBundleExtra(SearchManager.APP_DATA);
                        if (appData != null) {
                            source = appData.getString(Search.SOURCE);
                        }
                        if (TextUtils.isEmpty(source)) {
                            source = GOOGLE_SEARCH_SOURCE_UNKNOWN;
                        }
                        url = url.replace(searchSource, "&source=android-"+source+"&");
                    }
                }
            }
        }
        return new UrlData(url, headers, intent);
    }

    /**
     * Launches the default web search activity with the query parameters if the given intent's data
     * are identified as plain search terms and not URLs/shortcuts.
     * @return true if the intent was handled and web search activity was launched, false if not.
     */
    static boolean handleWebSearchIntent(Activity activity,
            Controller controller, Intent intent) {
        if (intent == null) return false;

        String url = null;
        final String action = intent.getAction();
        if (RecognizerResultsIntent.ACTION_VOICE_SEARCH_RESULTS.equals(
                action)) {
            return false;
        }
        if (Intent.ACTION_VIEW.equals(action)) {
            Uri data = intent.getData();
            if (data != null) url = data.toString();
        } else if (Intent.ACTION_SEARCH.equals(action)
                || MediaStore.INTENT_ACTION_MEDIA_SEARCH.equals(action)
                || Intent.ACTION_WEB_SEARCH.equals(action)) {
            url = intent.getStringExtra(SearchManager.QUERY);
        }
        return handleWebSearchRequest(activity, controller, url,
                intent.getBundleExtra(SearchManager.APP_DATA),
                intent.getStringExtra(SearchManager.EXTRA_DATA_KEY));
    }

    /**
     * Launches the default web search activity with the query parameters if the given url string
     * was identified as plain search terms and not URL/shortcut.
     * @return true if the request was handled and web search activity was launched, false if not.
     */
    private static boolean handleWebSearchRequest(Activity activity,
            Controller controller, String inUrl, Bundle appData,
            String extraData) {
        if (inUrl == null) return false;

        // In general, we shouldn't modify URL from Intent.
        // But currently, we get the user-typed URL from search box as well.
        String url = UrlUtils.fixUrl(inUrl).trim();
        if (TextUtils.isEmpty(url)) return false;

        // URLs are handled by the regular flow of control, so
        // return early.
        if (Patterns.WEB_URL.matcher(url).matches()
                || UrlUtils.ACCEPTED_URI_SCHEMA.matcher(url).matches()) {
            return false;
        }

        final ContentResolver cr = activity.getContentResolver();
        final String newUrl = url;
        if (controller == null || controller.getTabControl() == null
                || controller.getTabControl().getCurrentWebView() == null
                || !controller.getTabControl().getCurrentWebView()
                .isPrivateBrowsingEnabled()) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... unused) {
                        Browser.addSearchUrl(cr, newUrl);
                    return null;
                }
            }.execute();
        }

        SearchEngine searchEngine = BrowserSettings.getInstance().getSearchEngine();
        if (searchEngine == null) return false;
        searchEngine.startSearch(activity, url, appData, extraData);

        return true;
    }

    /**
     * A UrlData class to abstract how the content will be set to WebView.
     * This base class uses loadUrl to show the content.
     */
    static class UrlData {
        final String mUrl;
        final Map<String, String> mHeaders;
        final Intent mVoiceIntent;

        UrlData(String url) {
            this.mUrl = url;
            this.mHeaders = null;
            this.mVoiceIntent = null;
        }

        UrlData(String url, Map<String, String> headers, Intent intent) {
            this.mUrl = url;
            this.mHeaders = headers;
            if (RecognizerResultsIntent.ACTION_VOICE_SEARCH_RESULTS
                    .equals(intent.getAction())) {
                this.mVoiceIntent = intent;
            } else {
                this.mVoiceIntent = null;
            }
        }

        boolean isEmpty() {
            return mVoiceIntent == null && (mUrl == null || mUrl.length() == 0);
        }

        /**
         * Load this UrlData into the given Tab.  Use loadUrlDataIn to update
         * the title bar as well.
         */
        public void loadIn(Tab t) {
            if (mVoiceIntent != null) {
                t.activateVoiceSearchMode(mVoiceIntent);
            } else {
                t.getWebView().loadUrl(mUrl, mHeaders);
            }
        }
    }

    private static boolean needsRlz(Uri uri) {
        if ((uri.getQueryParameter("rlz") == null) &&
            (uri.getQueryParameter("q") != null) &&
            UrlUtils.isGoogleUri(uri)) {
            return true;
        }
        return false;
    }

    private static Uri addRlzParameter(Uri uri, String rlz) {
        if (rlz.isEmpty()) {
            return uri;
        }
        return uri.buildUpon().appendQueryParameter("rlz", rlz).build();
    }
}
