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

package com.android.server.connectivity;

import android.app.PendingIntent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseBooleanArray;
import java.util.Arrays;
import java.util.HashMap;

import com.android.internal.util.MessageUtils;
import com.android.server.connectivity.NetworkNotificationManager;
import com.android.server.connectivity.NetworkNotificationManager.NotificationType;

import static android.net.ConnectivityManager.NETID_UNSET;

/**
 * Class that monitors default network linger events and possibly notifies the user of network
 * switches.
 *
 * This class is not thread-safe and all its methods must be called on the ConnectivityService
 * handler thread.
 */
public class LingerMonitor {

    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private static final String TAG = LingerMonitor.class.getSimpleName();

    private static final HashMap<String, Integer> sTransportNames = makeTransportToNameMap();
    private static final Intent CELLULAR_SETTINGS = new Intent().setComponent(new ComponentName(
            "com.android.settings", "com.android.settings.Settings$DataUsageSummaryActivity"));

    private static final int NOTIFY_TYPE_NONE = 0;
    private static final int NOTIFY_TYPE_NOTIFICATION = 1;
    private static final int NOTIFY_TYPE_TOAST = 2;

    private static SparseArray<String> sNotifyTypeNames = MessageUtils.findMessageNames(
            new Class[] { LingerMonitor.class }, new String[]{ "NOTIFY_TYPE_" });

    private final Context mContext;
    private final NetworkNotificationManager mNotifier;

    /** Current notifications. Maps the netId we switched away from to the netId we switched to. */
    private final SparseIntArray mNotifications = new SparseIntArray();

    /** Whether we ever notified that we switched away from a particular network. */
    private final SparseBooleanArray mEverNotified = new SparseBooleanArray();

    public LingerMonitor(Context context, NetworkNotificationManager notifier) {
        mContext = context;
        mNotifier = notifier;
    }

    private static HashMap<String, Integer> makeTransportToNameMap() {
        SparseArray<String> numberToName = MessageUtils.findMessageNames(
            new Class[] { NetworkCapabilities.class }, new String[]{ "TRANSPORT_" });
        HashMap<String, Integer> nameToNumber = new HashMap<>();
        for (int i = 0; i < numberToName.size(); i++) {
            // MessageUtils will fail to initialize if there are duplicate constant values, so there
            // are no duplicates here.
            nameToNumber.put(numberToName.valueAt(i), numberToName.keyAt(i));
        }
        return nameToNumber;
    }

    private static boolean hasTransport(NetworkAgentInfo nai, int transport) {
        return nai.networkCapabilities.hasTransport(transport);
    }

    private int getNotificationSource(NetworkAgentInfo toNai) {
        for (int i = 0; i < mNotifications.size(); i++) {
            if (mNotifications.valueAt(i) == toNai.network.netId) {
                return mNotifications.keyAt(i);
            }
        }
        return NETID_UNSET;
    }

    private boolean everNotified(NetworkAgentInfo nai) {
        return mEverNotified.get(nai.network.netId, false);
    }

    private boolean isNotificationEnabled(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        // TODO: Evaluate moving to CarrierConfigManager.
        String[] notifySwitches = mContext.getResources().getStringArray(
                com.android.internal.R.array.config_networkNotifySwitches);

        if (VDBG) {
            Log.d(TAG, "Notify on network switches: " + Arrays.toString(notifySwitches));
        }

        for (String notifySwitch : notifySwitches) {
            if (TextUtils.isEmpty(notifySwitch)) continue;
            String[] transports = notifySwitch.split("-", 2);
            if (transports.length != 2) {
                Log.e(TAG, "Invalid network switch notification configuration: " + notifySwitch);
                continue;
            }
            int fromTransport = sTransportNames.get("TRANSPORT_" + transports[0]);
            int toTransport = sTransportNames.get("TRANSPORT_" + transports[1]);
            if (hasTransport(fromNai, fromTransport) && hasTransport(toNai, toTransport)) {
                return true;
            }
        }

        return false;
    }

    private void showNotification(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        PendingIntent pendingIntent = PendingIntent.getActivityAsUser(
                mContext, 0, CELLULAR_SETTINGS, PendingIntent.FLAG_CANCEL_CURRENT, null,
                UserHandle.CURRENT);

        mNotifier.showNotification(fromNai.network.netId, NotificationType.NETWORK_SWITCH,
                fromNai, toNai, pendingIntent, true);
    }

    // Removes any notification that was put up as a result of switching to nai.
    private void maybeStopNotifying(NetworkAgentInfo nai) {
        int fromNetId = getNotificationSource(nai);
        if (fromNetId != NETID_UNSET) {
            mNotifications.delete(fromNetId);
            mNotifier.clearNotification(fromNetId);
            // Toasts can't be deleted.
        }
    }

    // Notify the user of a network switch using a notification or a toast.
    private void notify(NetworkAgentInfo fromNai, NetworkAgentInfo toNai, boolean forceToast) {
        boolean notify = false;
        int notifyType = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_networkNotifySwitchType);

        if (notifyType == NOTIFY_TYPE_NOTIFICATION && forceToast) {
            notifyType = NOTIFY_TYPE_TOAST;
        }

        switch (notifyType) {
            case NOTIFY_TYPE_NONE:
                break;
            case NOTIFY_TYPE_NOTIFICATION:
                showNotification(fromNai, toNai);
                notify = true;
                break;
            case NOTIFY_TYPE_TOAST:
                mNotifier.showToast(fromNai, toNai);
                notify = true;
                break;
            default:
                Log.e(TAG, "Unknown notify type " + notifyType);
        }

        if (VDBG) {
            Log.d(TAG, "Notify type: " + sNotifyTypeNames.get(notifyType, "" + notifyType));
        }

        if (notify) {
            if (DBG) {
                Log.d(TAG, "Notifying switch from=" + fromNai.name() + " to=" + toNai.name() +
                        " type=" + sNotifyTypeNames.get(notifyType, "unknown(" + notifyType + ")"));
            }
            mNotifications.put(fromNai.network.netId, toNai.network.netId);
            mEverNotified.put(fromNai.network.netId, true);
        }
    }

    // The default network changed from fromNai to toNai due to a change in score.
    public void noteLingerDefaultNetwork(NetworkAgentInfo fromNai, NetworkAgentInfo toNai) {
        if (VDBG) {
            Log.d(TAG, "noteLingerDefaultNetwork from=" + fromNai.name() +
                    " everValidated=" + fromNai.everValidated +
                    " lastValidated=" + fromNai.lastValidated +
                    " to=" + toNai.name());
        }

        // If we are currently notifying the user because the device switched to fromNai, now that
        // we are switching away from it we should remove the notification. This includes the case
        // where we switch back to toNai because its score improved again (e.g., because it regained
        // Internet access).
        maybeStopNotifying(fromNai);

        // If this network never validated, don't notify. Otherwise, we could do things like:
        //
        // 1. Unvalidated wifi connects.
        // 2. Unvalidated mobile data connects.
        // 3. Cell validates, and we show a notification.
        // or:
        // 1. User connects to wireless printer.
        // 2. User turns on cellular data.
        // 3. We show a notification.
        if (!fromNai.everValidated) return;

        // If this network is a captive portal, don't notify. This cannot happen on initial connect
        // to a captive portal, because the everValidated check above will fail. However, it can
        // happen if the captive portal reasserts itself (e.g., because its timeout fires). In that
        // case, as soon as the captive portal reasserts itself, we'll show a sign-in notification.
        // We don't want to overwrite that notification with this one; the user has already been
        // notified, and of the two, the captive portal notification is the more useful one because
        // it allows the user to sign in to the captive portal. In this case, display a toast
        // in addition to the captive portal notification.
        //
        // Note that if the network we switch to is already up when the captive portal reappears,
        // this won't work because NetworkMonitor tells ConnectivityService that the network is
        // unvalidated (causing a switch) before asking it to show the sign in notification. In this
        // case, the toast won't show and we'll only display the sign in notification. This is the
        // best we can do at this time.
        boolean forceToast = fromNai.networkCapabilities.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);

        // Only show the notification once, in order to avoid irritating the user every time.
        // TODO: should we do this?
        if (everNotified(fromNai)) {
            if (VDBG) {
                Log.d(TAG, "Not notifying handover from " + fromNai.name() + ", already notified");
            }
            return;
        }

        if (isNotificationEnabled(fromNai, toNai)) {
            notify(fromNai, toNai, forceToast);
        }
    }

    public void noteDisconnect(NetworkAgentInfo nai) {
        mNotifications.delete(nai.network.netId);
        mEverNotified.delete(nai.network.netId);
        maybeStopNotifying(nai);
        // No need to cancel notifications on nai: NetworkMonitor does that on disconnect.
    }
}
