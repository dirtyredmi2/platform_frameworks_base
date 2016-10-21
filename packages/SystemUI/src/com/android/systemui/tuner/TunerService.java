/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.systemui.DemoMode;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.settings.CurrentUserTracker;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import java.util.HashMap;
import java.util.Set;


public class TunerService extends SystemUI {

    private final Observer mObserver = new Observer();
    // Map of Uris we listen on to their settings keys.
    private final ArrayMap<Uri, String> mListeningUris = new ArrayMap<>();
    // Map of settings keys to the listener.
    private final HashMap<String, Set<Tunable>> mTunableLookup = new HashMap<>();

    private ContentResolver mContentResolver;
    private int mCurrentUser;
    private CurrentUserTracker mUserTracker;

    @Override
    public void start() {
        mContentResolver = mContext.getContentResolver();

        putComponent(TunerService.class, this);

        mCurrentUser = ActivityManager.getCurrentUser();
        mUserTracker = new CurrentUserTracker(mContext) {
            @Override
            public void onUserSwitched(int newUserId) {
                mCurrentUser = newUserId;
                reloadAll();
                reregisterAll();
            }
        };
        mUserTracker.startTracking();
    }

    public String getValue(String setting) {
        return Settings.Secure.getStringForUser(mContentResolver, setting, mCurrentUser);
    }

    public void setValue(String setting, String value) {
         Settings.Secure.putStringForUser(mContentResolver, setting, value, mCurrentUser);
    }

    public int getValue(String setting, int def) {
        return Settings.Secure.getIntForUser(mContentResolver, setting, def, mCurrentUser);
    }

    public void setValue(String setting, int value) {
         Settings.Secure.putIntForUser(mContentResolver, setting, value, mCurrentUser);
    }

    public void addTunable(Tunable tunable, String... keys) {
        for (String key : keys) {
            addTunable(tunable, key);
        }
    }

    private void addTunable(Tunable tunable, String key) {
        if (!mTunableLookup.containsKey(key)) {
            mTunableLookup.put(key, new ArraySet<Tunable>());
        }
        mTunableLookup.get(key).add(tunable);
        Uri uri = Settings.Secure.getUriFor(key);
        if (!mListeningUris.containsKey(uri)) {
            mListeningUris.put(uri, key);
            mContentResolver.registerContentObserver(uri, true, mObserver, mCurrentUser);
        }
        // Send the first state.
        String value = Settings.Secure.getStringForUser(mContentResolver, key, mCurrentUser);
        tunable.onTuningChanged(key, value);
    }

    public void removeTunable(Tunable tunable) {
        for (Set<Tunable> list : mTunableLookup.values()) {
            list.remove(tunable);
        }
    }

    protected void reregisterAll() {
        if (mListeningUris.size() == 0) {
            return;
        }
        mContentResolver.unregisterContentObserver(mObserver);
        for (Uri uri : mListeningUris.keySet()) {
            mContentResolver.registerContentObserver(uri, true, mObserver, mCurrentUser);
        }
    }

    public void reloadSetting(Uri uri) {
        String key = mListeningUris.get(uri);
        Set<Tunable> tunables = mTunableLookup.get(key);
        if (tunables == null) {
            return;
        }
        String value = Settings.Secure.getStringForUser(mContentResolver, key, mCurrentUser);
        for (Tunable tunable : tunables) {
            tunable.onTuningChanged(key, value);
        }
    }

    private void reloadAll() {
        for (String key : mTunableLookup.keySet()) {
            String value = Settings.Secure.getStringForUser(mContentResolver, key,
                    mCurrentUser);
            for (Tunable tunable : mTunableLookup.get(key)) {
                tunable.onTuningChanged(key, value);
            }
        }
    }

    // Only used in other processes, such as the tuner.
    private static TunerService sInstance;

    public static TunerService get(Context context) {
        TunerService service = null;
        if (context.getApplicationContext() instanceof SystemUIApplication) {
            SystemUIApplication sysUi = (SystemUIApplication) context.getApplicationContext();
            service = sysUi.getComponent(TunerService.class);
        }
        if (service == null) {
            // Can't get it as a component, must in the tuner, lets just create one for now.
            return getStaticService(context);
        }
        return service;
    }

    private static TunerService getStaticService(Context context) {
        if (sInstance == null) {
            sInstance = new TunerService();
            sInstance.mContext = context.getApplicationContext();
            sInstance.mComponents = new HashMap<>();
            sInstance.start();
        }
        return sInstance;
    }

    public static final void showResetRequest(final Context context, final Runnable onDisabled) {
        SystemUIDialog dialog = new SystemUIDialog(context);
        dialog.setShowForAllUsers(true);
        dialog.setMessage(R.string.remove_from_settings_prompt);
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE, context.getString(R.string.cancel),
                (OnClickListener) null);
        dialog.setButton(DialogInterface.BUTTON_POSITIVE,
                context.getString(R.string.guest_exit_guest_dialog_remove), new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (onDisabled != null) {
                    onDisabled.run();
                }
            }
        });
        dialog.show();
    }

    public static final boolean isTunerEnabled(Context context) {
        return true;
    }

    private static Context userContext(Context context) {
        try {
            return context.createPackageContextAsUser(context.getPackageName(), 0,
                    new UserHandle(ActivityManager.getCurrentUser()));
        } catch (NameNotFoundException e) {
            return context;
        }
    }

    private class Observer extends ContentObserver {
        public Observer() {
            super(new Handler(Looper.getMainLooper()));
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId == ActivityManager.getCurrentUser()) {
                reloadSetting(uri);
            }
        }
    }

    public interface Tunable {
        void onTuningChanged(String key, String newValue);
    }
}
