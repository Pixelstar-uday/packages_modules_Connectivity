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

package com.android.cts.net.hostside;

import static android.net.ConnectivityManager.ACTION_RESTRICT_BACKGROUND_CHANGED;
import static android.os.BatteryManager.BATTERY_PLUGGED_AC;
import static android.os.BatteryManager.BATTERY_PLUGGED_USB;
import static android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS;

import static com.android.cts.net.hostside.NetworkPolicyTestUtils.executeShellCommand;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.getConnectivityManager;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.getContext;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.getInstrumentation;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.getWifiManager;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.isDozeModeSupported;
import static com.android.cts.net.hostside.NetworkPolicyTestUtils.restrictBackgroundValueToString;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.app.Instrumentation;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo.DetailedState;
import android.net.NetworkInfo.State;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import org.junit.Rule;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Superclass for tests related to background network restrictions.
 */
@RunWith(NetworkPolicyTestRunner.class)
public abstract class AbstractRestrictBackgroundNetworkTestCase {
    public static final String TAG = "RestrictBackgroundNetworkTests";

    protected static final String TEST_PKG = "com.android.cts.net.hostside";
    protected static final String TEST_APP2_PKG = "com.android.cts.net.hostside.app2";

    private static final String TEST_APP2_ACTIVITY_CLASS = TEST_APP2_PKG + ".MyActivity";
    private static final String TEST_APP2_SERVICE_CLASS = TEST_APP2_PKG + ".MyForegroundService";

    private static final int SLEEP_TIME_SEC = 1;

    // Constants below must match values defined on app2's Common.java
    private static final String MANIFEST_RECEIVER = "ManifestReceiver";
    private static final String DYNAMIC_RECEIVER = "DynamicReceiver";

    private static final String ACTION_RECEIVER_READY =
            "com.android.cts.net.hostside.app2.action.RECEIVER_READY";
    static final String ACTION_SHOW_TOAST =
            "com.android.cts.net.hostside.app2.action.SHOW_TOAST";

    protected static final String NOTIFICATION_TYPE_CONTENT = "CONTENT";
    protected static final String NOTIFICATION_TYPE_DELETE = "DELETE";
    protected static final String NOTIFICATION_TYPE_FULL_SCREEN = "FULL_SCREEN";
    protected static final String NOTIFICATION_TYPE_BUNDLE = "BUNDLE";
    protected static final String NOTIFICATION_TYPE_ACTION = "ACTION";
    protected static final String NOTIFICATION_TYPE_ACTION_BUNDLE = "ACTION_BUNDLE";
    protected static final String NOTIFICATION_TYPE_ACTION_REMOTE_INPUT = "ACTION_REMOTE_INPUT";

    // TODO: Update BatteryManager.BATTERY_PLUGGED_ANY as @TestApi
    public static final int BATTERY_PLUGGED_ANY =
            BATTERY_PLUGGED_AC | BATTERY_PLUGGED_USB | BATTERY_PLUGGED_WIRELESS;

    private static final String NETWORK_STATUS_SEPARATOR = "\\|";
    private static final int SECOND_IN_MS = 1000;
    static final int NETWORK_TIMEOUT_MS = 15 * SECOND_IN_MS;
    private static int PROCESS_STATE_FOREGROUND_SERVICE;

    private static final String KEY_NETWORK_STATE_OBSERVER = TEST_PKG + ".observer";

    protected static final int TYPE_COMPONENT_ACTIVTIY = 0;
    protected static final int TYPE_COMPONENT_FOREGROUND_SERVICE = 1;

    private static final int BATTERY_STATE_TIMEOUT_MS = 5000;
    private static final int BATTERY_STATE_CHECK_INTERVAL_MS = 500;

    private static final int FOREGROUND_PROC_NETWORK_TIMEOUT_MS = 6000;

    // Must be higher than NETWORK_TIMEOUT_MS
    private static final int ORDERED_BROADCAST_TIMEOUT_MS = NETWORK_TIMEOUT_MS * 4;

    private static final IntentFilter BATTERY_CHANGED_FILTER =
            new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

    private static final String APP_NOT_FOREGROUND_ERROR = "app_not_fg";

    protected static final long TEMP_POWERSAVE_WHITELIST_DURATION_MS = 5_000; // 5 sec

    protected Context mContext;
    protected Instrumentation mInstrumentation;
    protected ConnectivityManager mCm;
    protected int mUid;
    private int mMyUid;
    private MyServiceClient mServiceClient;
    private String mDeviceIdleConstantsSetting;

    @Rule
    public final RuleChain mRuleChain = RuleChain.outerRule(new RequiredPropertiesRule())
            .around(new MeterednessConfigurationRule());

    protected void setUp() throws Exception {

        PROCESS_STATE_FOREGROUND_SERVICE = (Integer) ActivityManager.class
                .getDeclaredField("PROCESS_STATE_FOREGROUND_SERVICE").get(null);
        mInstrumentation = getInstrumentation();
        mContext = getContext();
        mCm = getConnectivityManager();
        mUid = getUid(TEST_APP2_PKG);
        mMyUid = getUid(mContext.getPackageName());
        mServiceClient = new MyServiceClient(mContext);
        mServiceClient.bind();
        mDeviceIdleConstantsSetting = "device_idle_constants";
        executeShellCommand("cmd netpolicy start-watching " + mUid);
        setAppIdle(false);

        Log.i(TAG, "Apps status:\n"
                + "\ttest app: uid=" + mMyUid + ", state=" + getProcessStateByUid(mMyUid) + "\n"
                + "\tapp2: uid=" + mUid + ", state=" + getProcessStateByUid(mUid));
    }

    protected void tearDown() throws Exception {
        executeShellCommand("cmd netpolicy stop-watching");
        mServiceClient.unbind();
    }

    protected int getUid(String packageName) throws Exception {
        return mContext.getPackageManager().getPackageUid(packageName, 0);
    }

    protected void assertRestrictBackgroundChangedReceived(int expectedCount) throws Exception {
        assertRestrictBackgroundChangedReceived(DYNAMIC_RECEIVER, expectedCount);
        assertRestrictBackgroundChangedReceived(MANIFEST_RECEIVER, 0);
    }

    protected void assertRestrictBackgroundChangedReceived(String receiverName, int expectedCount)
            throws Exception {
        int attempts = 0;
        int count = 0;
        final int maxAttempts = 5;
        do {
            attempts++;
            count = getNumberBroadcastsReceived(receiverName, ACTION_RESTRICT_BACKGROUND_CHANGED);
            if (count >= expectedCount) {
                break;
            }
            Log.d(TAG, "Expecting count " + expectedCount + " but actual is " + count + " after "
                    + attempts + " attempts; sleeping "
                    + SLEEP_TIME_SEC + " seconds before trying again");
            SystemClock.sleep(SLEEP_TIME_SEC * SECOND_IN_MS);
        } while (attempts <= maxAttempts);
        assertEquals("Number of expected broadcasts for " + receiverName + " not reached after "
                + maxAttempts * SLEEP_TIME_SEC + " seconds", expectedCount, count);
    }

    protected String sendOrderedBroadcast(Intent intent) throws Exception {
        return sendOrderedBroadcast(intent, ORDERED_BROADCAST_TIMEOUT_MS);
    }

    protected String sendOrderedBroadcast(Intent intent, int timeoutMs) throws Exception {
        final LinkedBlockingQueue<String> result = new LinkedBlockingQueue<>(1);
        Log.d(TAG, "Sending ordered broadcast: " + intent);
        mContext.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                final String resultData = getResultData();
                if (resultData == null) {
                    Log.e(TAG, "Received null data from ordered intent");
                    return;
                }
                result.offer(resultData);
            }
        }, null, 0, null, null);

        final String resultData = result.poll(timeoutMs, TimeUnit.MILLISECONDS);
        Log.d(TAG, "Ordered broadcast response after " + timeoutMs + "ms: " + resultData );
        return resultData;
    }

    protected int getNumberBroadcastsReceived(String receiverName, String action) throws Exception {
        return mServiceClient.getCounters(receiverName, action);
    }

    protected void assertRestrictBackgroundStatus(int expectedStatus) throws Exception {
        final String status = mServiceClient.getRestrictBackgroundStatus();
        assertNotNull("didn't get API status from app2", status);
        assertEquals(restrictBackgroundValueToString(expectedStatus),
                restrictBackgroundValueToString(Integer.parseInt(status)));
    }

    protected void assertBackgroundNetworkAccess(boolean expectAllowed) throws Exception {
        assertBackgroundState(); // Sanity check.
        assertNetworkAccess(expectAllowed /* expectAvailable */, false /* needScreenOn */);
    }

    protected void assertForegroundNetworkAccess() throws Exception {
        assertForegroundState(); // Sanity check.
        // We verified that app is in foreground state but if the screen turns-off while
        // verifying for network access, the app will go into background state (in case app's
        // foreground status was due to top activity). So, turn the screen on when verifying
        // network connectivity.
        assertNetworkAccess(true /* expectAvailable */, true /* needScreenOn */);
    }

    protected void assertForegroundServiceNetworkAccess() throws Exception {
        assertForegroundServiceState(); // Sanity check.
        assertNetworkAccess(true /* expectAvailable */, false /* needScreenOn */);
    }

    /**
     * Asserts that an app always have access while on foreground or running a foreground service.
     *
     * <p>This method will launch an activity and a foreground service to make the assertion, but
     * will finish the activity / stop the service afterwards.
     */
    protected void assertsForegroundAlwaysHasNetworkAccess() throws Exception{
        // Checks foreground first.
        launchComponentAndAssertNetworkAccess(TYPE_COMPONENT_ACTIVTIY);
        finishActivity();

        // Then foreground service
        launchComponentAndAssertNetworkAccess(TYPE_COMPONENT_FOREGROUND_SERVICE);
        stopForegroundService();
    }

    protected final void assertBackgroundState() throws Exception {
        final int maxTries = 30;
        ProcessState state = null;
        for (int i = 1; i <= maxTries; i++) {
            state = getProcessStateByUid(mUid);
            Log.v(TAG, "assertBackgroundState(): status for app2 (" + mUid + ") on attempt #" + i
                    + ": " + state);
            if (isBackground(state.state)) {
                return;
            }
            Log.d(TAG, "App not on background state (" + state + ") on attempt #" + i
                    + "; sleeping 1s before trying again");
            SystemClock.sleep(SECOND_IN_MS);
        }
        fail("App2 is not on background state after " + maxTries + " attempts: " + state );
    }

    protected final void assertForegroundState() throws Exception {
        final int maxTries = 30;
        ProcessState state = null;
        for (int i = 1; i <= maxTries; i++) {
            state = getProcessStateByUid(mUid);
            Log.v(TAG, "assertForegroundState(): status for app2 (" + mUid + ") on attempt #" + i
                    + ": " + state);
            if (!isBackground(state.state)) {
                return;
            }
            Log.d(TAG, "App not on foreground state on attempt #" + i
                    + "; sleeping 1s before trying again");
            turnScreenOn();
            SystemClock.sleep(SECOND_IN_MS);
        }
        fail("App2 is not on foreground state after " + maxTries + " attempts: " + state );
    }

    protected final void assertForegroundServiceState() throws Exception {
        final int maxTries = 30;
        ProcessState state = null;
        for (int i = 1; i <= maxTries; i++) {
            state = getProcessStateByUid(mUid);
            Log.v(TAG, "assertForegroundServiceState(): status for app2 (" + mUid + ") on attempt #"
                    + i + ": " + state);
            if (state.state == PROCESS_STATE_FOREGROUND_SERVICE) {
                return;
            }
            Log.d(TAG, "App not on foreground service state on attempt #" + i
                    + "; sleeping 1s before trying again");
            SystemClock.sleep(SECOND_IN_MS);
        }
        fail("App2 is not on foreground service state after " + maxTries + " attempts: " + state );
    }

    /**
     * Returns whether an app state should be considered "background" for restriction purposes.
     */
    protected boolean isBackground(int state) {
        return state > PROCESS_STATE_FOREGROUND_SERVICE;
    }

    /**
     * Asserts whether the active network is available or not.
     */
    private void assertNetworkAccess(boolean expectAvailable, boolean needScreenOn)
            throws Exception {
        final int maxTries = 5;
        String error = null;
        int timeoutMs = 500;

        for (int i = 1; i <= maxTries; i++) {
            error = checkNetworkAccess(expectAvailable);

            if (error.isEmpty()) return;

            // TODO: ideally, it should retry only when it cannot connect to an external site,
            // or no retry at all! But, currently, the initial change fails almost always on
            // battery saver tests because the netd changes are made asynchronously.
            // Once b/27803922 is fixed, this retry mechanism should be revisited.

            Log.w(TAG, "Network status didn't match for expectAvailable=" + expectAvailable
                    + " on attempt #" + i + ": " + error + "\n"
                    + "Sleeping " + timeoutMs + "ms before trying again");
            if (needScreenOn) {
                turnScreenOn();
            }
            // No sleep after the last turn
            if (i < maxTries) {
                SystemClock.sleep(timeoutMs);
            }
            // Exponential back-off.
            timeoutMs = Math.min(timeoutMs*2, NETWORK_TIMEOUT_MS);
        }
        fail("Invalid state for expectAvailable=" + expectAvailable + " after " + maxTries
                + " attempts.\nLast error: " + error);
    }

    /**
     * Checks whether the network is available as expected.
     *
     * @return error message with the mismatch (or empty if assertion passed).
     */
    private String checkNetworkAccess(boolean expectAvailable) throws Exception {
        final String resultData = mServiceClient.checkNetworkStatus();
        return checkForAvailabilityInResultData(resultData, expectAvailable);
    }

    private String checkForAvailabilityInResultData(String resultData, boolean expectAvailable) {
        if (resultData == null) {
            assertNotNull("Network status from app2 is null", resultData);
        }
        // Network status format is described on MyBroadcastReceiver.checkNetworkStatus()
        final String[] parts = resultData.split(NETWORK_STATUS_SEPARATOR);
        assertEquals("Wrong network status: " + resultData, 5, parts.length); // Sanity check
        final State state = parts[0].equals("null") ? null : State.valueOf(parts[0]);
        final DetailedState detailedState = parts[1].equals("null")
                ? null : DetailedState.valueOf(parts[1]);
        final boolean connected = Boolean.valueOf(parts[2]);
        final String connectionCheckDetails = parts[3];
        final String networkInfo = parts[4];

        final StringBuilder errors = new StringBuilder();
        final State expectedState;
        final DetailedState expectedDetailedState;
        if (expectAvailable) {
            expectedState = State.CONNECTED;
            expectedDetailedState = DetailedState.CONNECTED;
        } else {
            expectedState = State.DISCONNECTED;
            expectedDetailedState = DetailedState.BLOCKED;
        }

        if (expectAvailable != connected) {
            errors.append(String.format("External site connection failed: expected %s, got %s\n",
                    expectAvailable, connected));
        }
        if (expectedState != state || expectedDetailedState != detailedState) {
            errors.append(String.format("Connection state mismatch: expected %s/%s, got %s/%s\n",
                    expectedState, expectedDetailedState, state, detailedState));
        }

        if (errors.length() > 0) {
            errors.append("\tnetworkInfo: " + networkInfo + "\n");
            errors.append("\tconnectionCheckDetails: " + connectionCheckDetails + "\n");
        }
        return errors.toString();
    }

    /**
     * Runs a Shell command which is not expected to generate output.
     */
    protected void executeSilentShellCommand(String command) {
        final String result = executeShellCommand(command);
        assertTrue("Command '" + command + "' failed: " + result, result.trim().isEmpty());
    }

    /**
     * Asserts the result of a command, wait and re-running it a couple times if necessary.
     */
    protected void assertDelayedShellCommand(String command, final String expectedResult)
            throws Exception {
        assertDelayedShellCommand(command, 5, 1, expectedResult);
    }

    protected void assertDelayedShellCommand(String command, int maxTries, int napTimeSeconds,
            final String expectedResult) throws Exception {
        assertDelayedShellCommand(command, maxTries, napTimeSeconds, new ExpectResultChecker() {

            @Override
            public boolean isExpected(String result) {
                return expectedResult.equals(result);
            }

            @Override
            public String getExpected() {
                return expectedResult;
            }
        });
    }

    protected void assertDelayedShellCommand(String command, int maxTries, int napTimeSeconds,
            ExpectResultChecker checker) throws Exception {
        String result = "";
        for (int i = 1; i <= maxTries; i++) {
            result = executeShellCommand(command).trim();
            if (checker.isExpected(result)) return;
            Log.v(TAG, "Command '" + command + "' returned '" + result + " instead of '"
                    + checker.getExpected() + "' on attempt #" + i
                    + "; sleeping " + napTimeSeconds + "s before trying again");
            SystemClock.sleep(napTimeSeconds * SECOND_IN_MS);
        }
        fail("Command '" + command + "' did not return '" + checker.getExpected() + "' after "
                + maxTries
                + " attempts. Last result: '" + result + "'");
    }

    protected void addRestrictBackgroundWhitelist(int uid) throws Exception {
        executeShellCommand("cmd netpolicy add restrict-background-whitelist " + uid);
        assertRestrictBackgroundWhitelist(uid, true);
        // UID policies live by the Highlander rule: "There can be only one".
        // Hence, if app is whitelisted, it should not be blacklisted.
        assertRestrictBackgroundBlacklist(uid, false);
    }

    protected void removeRestrictBackgroundWhitelist(int uid) throws Exception {
        executeShellCommand("cmd netpolicy remove restrict-background-whitelist " + uid);
        assertRestrictBackgroundWhitelist(uid, false);
    }

    protected void assertRestrictBackgroundWhitelist(int uid, boolean expected) throws Exception {
        assertRestrictBackground("restrict-background-whitelist", uid, expected);
    }

    protected void addRestrictBackgroundBlacklist(int uid) throws Exception {
        executeShellCommand("cmd netpolicy add restrict-background-blacklist " + uid);
        assertRestrictBackgroundBlacklist(uid, true);
        // UID policies live by the Highlander rule: "There can be only one".
        // Hence, if app is blacklisted, it should not be whitelisted.
        assertRestrictBackgroundWhitelist(uid, false);
    }

    protected void removeRestrictBackgroundBlacklist(int uid) throws Exception {
        executeShellCommand("cmd netpolicy remove restrict-background-blacklist " + uid);
        assertRestrictBackgroundBlacklist(uid, false);
    }

    protected void assertRestrictBackgroundBlacklist(int uid, boolean expected) throws Exception {
        assertRestrictBackground("restrict-background-blacklist", uid, expected);
    }

    protected void addAppIdleWhitelist(int uid) throws Exception {
        executeShellCommand("cmd netpolicy add app-idle-whitelist " + uid);
        assertAppIdleWhitelist(uid, true);
    }

    protected void removeAppIdleWhitelist(int uid) throws Exception {
        executeShellCommand("cmd netpolicy remove app-idle-whitelist " + uid);
        assertAppIdleWhitelist(uid, false);
    }

    protected void assertAppIdleWhitelist(int uid, boolean expected) throws Exception {
        assertRestrictBackground("app-idle-whitelist", uid, expected);
    }

    private void assertRestrictBackground(String list, int uid, boolean expected) throws Exception {
        final int maxTries = 5;
        boolean actual = false;
        final String expectedUid = Integer.toString(uid);
        String uids = "";
        for (int i = 1; i <= maxTries; i++) {
            final String output =
                    executeShellCommand("cmd netpolicy list " + list);
            uids = output.split(":")[1];
            for (String candidate : uids.split(" ")) {
                actual = candidate.trim().equals(expectedUid);
                if (expected == actual) {
                    return;
                }
            }
            Log.v(TAG, list + " check for uid " + uid + " doesn't match yet (expected "
                    + expected + ", got " + actual + "); sleeping 1s before polling again");
            SystemClock.sleep(SECOND_IN_MS);
        }
        fail(list + " check for uid " + uid + " failed: expected " + expected + ", got " + actual
                + ". Full list: " + uids);
    }

    protected void addTempPowerSaveModeWhitelist(String packageName, long duration)
            throws Exception {
        Log.i(TAG, "Adding pkg " + packageName + " to temp-power-save-mode whitelist");
        executeShellCommand("dumpsys deviceidle tempwhitelist -d " + duration + " " + packageName);
    }

    protected void assertPowerSaveModeWhitelist(String packageName, boolean expected)
            throws Exception {
        // TODO: currently the power-save mode is behaving like idle, but once it changes, we'll
        // need to use netpolicy for whitelisting
        assertDelayedShellCommand("dumpsys deviceidle whitelist =" + packageName,
                Boolean.toString(expected));
    }

    protected void addPowerSaveModeWhitelist(String packageName) throws Exception {
        Log.i(TAG, "Adding package " + packageName + " to power-save-mode whitelist");
        // TODO: currently the power-save mode is behaving like idle, but once it changes, we'll
        // need to use netpolicy for whitelisting
        executeShellCommand("dumpsys deviceidle whitelist +" + packageName);
        assertPowerSaveModeWhitelist(packageName, true); // Sanity check
    }

    protected void removePowerSaveModeWhitelist(String packageName) throws Exception {
        Log.i(TAG, "Removing package " + packageName + " from power-save-mode whitelist");
        // TODO: currently the power-save mode is behaving like idle, but once it changes, we'll
        // need to use netpolicy for whitelisting
        executeShellCommand("dumpsys deviceidle whitelist -" + packageName);
        assertPowerSaveModeWhitelist(packageName, false); // Sanity check
    }

    protected void assertPowerSaveModeExceptIdleWhitelist(String packageName, boolean expected)
            throws Exception {
        // TODO: currently the power-save mode is behaving like idle, but once it changes, we'll
        // need to use netpolicy for whitelisting
        assertDelayedShellCommand("dumpsys deviceidle except-idle-whitelist =" + packageName,
                Boolean.toString(expected));
    }

    protected void addPowerSaveModeExceptIdleWhitelist(String packageName) throws Exception {
        Log.i(TAG, "Adding package " + packageName + " to power-save-mode-except-idle whitelist");
        // TODO: currently the power-save mode is behaving like idle, but once it changes, we'll
        // need to use netpolicy for whitelisting
        executeShellCommand("dumpsys deviceidle except-idle-whitelist +" + packageName);
        assertPowerSaveModeExceptIdleWhitelist(packageName, true); // Sanity check
    }

    protected void removePowerSaveModeExceptIdleWhitelist(String packageName) throws Exception {
        Log.i(TAG, "Removing package " + packageName
                + " from power-save-mode-except-idle whitelist");
        // TODO: currently the power-save mode is behaving like idle, but once it changes, we'll
        // need to use netpolicy for whitelisting
        executeShellCommand("dumpsys deviceidle except-idle-whitelist reset");
        assertPowerSaveModeExceptIdleWhitelist(packageName, false); // Sanity check
    }

    protected void turnBatteryOn() throws Exception {
        executeSilentShellCommand("cmd battery unplug");
        executeSilentShellCommand("cmd battery set status "
                + BatteryManager.BATTERY_STATUS_DISCHARGING);
        assertBatteryState(false);
    }

    protected void turnBatteryOff() throws Exception {
        executeSilentShellCommand("cmd battery set ac " + BATTERY_PLUGGED_ANY);
        executeSilentShellCommand("cmd battery set level 100");
        executeSilentShellCommand("cmd battery set status "
                + BatteryManager.BATTERY_STATUS_CHARGING);
        assertBatteryState(true);
    }

    private void assertBatteryState(boolean pluggedIn) throws Exception {
        final long endTime = SystemClock.elapsedRealtime() + BATTERY_STATE_TIMEOUT_MS;
        while (isDevicePluggedIn() != pluggedIn && SystemClock.elapsedRealtime() <= endTime) {
            Thread.sleep(BATTERY_STATE_CHECK_INTERVAL_MS);
        }
        if (isDevicePluggedIn() != pluggedIn) {
            fail("Timed out waiting for the plugged-in state to change,"
                    + " expected pluggedIn: " + pluggedIn);
        }
    }

    private boolean isDevicePluggedIn() {
        final Intent batteryIntent = mContext.registerReceiver(null, BATTERY_CHANGED_FILTER);
        return batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) > 0;
    }

    protected void turnScreenOff() throws Exception {
        executeSilentShellCommand("input keyevent KEYCODE_SLEEP");
    }

    protected void turnScreenOn() throws Exception {
        executeSilentShellCommand("input keyevent KEYCODE_WAKEUP");
        executeSilentShellCommand("wm dismiss-keyguard");
    }

    protected void setBatterySaverMode(boolean enabled) throws Exception {
        Log.i(TAG, "Setting Battery Saver Mode to " + enabled);
        if (enabled) {
            turnBatteryOn();
            executeSilentShellCommand("cmd power set-mode 1");
        } else {
            executeSilentShellCommand("cmd power set-mode 0");
            turnBatteryOff();
        }
    }

    protected void setDozeMode(boolean enabled) throws Exception {
        // Sanity check, since tests should check beforehand....
        assertTrue("Device does not support Doze Mode", isDozeModeSupported());

        Log.i(TAG, "Setting Doze Mode to " + enabled);
        if (enabled) {
            turnBatteryOn();
            turnScreenOff();
            executeShellCommand("dumpsys deviceidle force-idle deep");
        } else {
            turnScreenOn();
            turnBatteryOff();
            executeShellCommand("dumpsys deviceidle unforce");
        }
        // Sanity check.
        assertDozeMode(enabled);
    }

    protected void assertDozeMode(boolean enabled) throws Exception {
        assertDelayedShellCommand("dumpsys deviceidle get deep", enabled ? "IDLE" : "ACTIVE");
    }

    protected void setAppIdle(boolean enabled) throws Exception {
        Log.i(TAG, "Setting app idle to " + enabled);
        executeSilentShellCommand("am set-inactive " + TEST_APP2_PKG + " " + enabled );
        assertAppIdle(enabled); // Sanity check
    }

    protected void assertAppIdle(boolean enabled) throws Exception {
        try {
            assertDelayedShellCommand("am get-inactive " + TEST_APP2_PKG, 15, 2, "Idle=" + enabled);
        } catch (Throwable e) {
            throw e;
        }
    }

    /**
     * Starts a service that will register a broadcast receiver to receive
     * {@code RESTRICT_BACKGROUND_CHANGE} intents.
     * <p>
     * The service must run in a separate app because otherwise it would be killed every time
     * {@link #runDeviceTests(String, String)} is executed.
     */
    protected void registerBroadcastReceiver() throws Exception {
        mServiceClient.registerBroadcastReceiver();

        final Intent intent = new Intent(ACTION_RECEIVER_READY)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        // Wait until receiver is ready.
        final int maxTries = 10;
        for (int i = 1; i <= maxTries; i++) {
            final String message = sendOrderedBroadcast(intent, SECOND_IN_MS * 4);
            Log.d(TAG, "app2 receiver acked: " + message);
            if (message != null) {
                return;
            }
            Log.v(TAG, "app2 receiver is not ready yet; sleeping 1s before polling again");
            SystemClock.sleep(SECOND_IN_MS);
        }
        fail("app2 receiver is not ready");
    }

    protected void registerNetworkCallback(INetworkCallback cb) throws Exception {
        mServiceClient.registerNetworkCallback(cb);
    }

    /**
     * Registers a {@link NotificationListenerService} implementation that will execute the
     * notification actions right after the notification is sent.
     */
    protected void registerNotificationListenerService() throws Exception {
        executeShellCommand("cmd notification allow_listener "
                + MyNotificationListenerService.getId());
        final NotificationManager nm = mContext.getSystemService(NotificationManager.class);
        final ComponentName listenerComponent = MyNotificationListenerService.getComponentName();
        assertTrue(listenerComponent + " has not been granted access",
                nm.isNotificationListenerAccessGranted(listenerComponent));
    }

    protected void setPendingIntentWhitelistDuration(int durationMs) throws Exception {
        executeSilentShellCommand(String.format(
                "settings put global %s %s=%d", mDeviceIdleConstantsSetting,
                "notification_whitelist_duration", durationMs));
    }

    protected void resetDeviceIdleSettings() throws Exception {
        executeShellCommand(String.format("settings delete global %s",
                mDeviceIdleConstantsSetting));
    }

    protected void launchComponentAndAssertNetworkAccess(int type) throws Exception {
        if (type == TYPE_COMPONENT_FOREGROUND_SERVICE) {
            startForegroundService();
            assertForegroundServiceNetworkAccess();
            return;
        } else if (type == TYPE_COMPONENT_ACTIVTIY) {
            turnScreenOn();
            // Wait for screen-on state to propagate through the system.
            SystemClock.sleep(2000);
            final CountDownLatch latch = new CountDownLatch(1);
            final Intent launchIntent = getIntentForComponent(type);
            final Bundle extras = new Bundle();
            final String[] errors = new String[]{null};
            extras.putBinder(KEY_NETWORK_STATE_OBSERVER, getNewNetworkStateObserver(latch, errors));
            launchIntent.putExtras(extras);
            mContext.startActivity(launchIntent);
            if (latch.await(FOREGROUND_PROC_NETWORK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                if (!errors[0].isEmpty()) {
                    if (errors[0] == APP_NOT_FOREGROUND_ERROR) {
                        // App didn't come to foreground when the activity is started, so try again.
                        assertForegroundNetworkAccess();
                    } else {
                        fail("Network is not available for app2 (" + mUid + "): " + errors[0]);
                    }
                }
            } else {
                fail("Timed out waiting for network availability status from app2 (" + mUid + ")");
            }
        } else {
            throw new IllegalArgumentException("Unknown type: " + type);
        }
    }

    private void startForegroundService() throws Exception {
        final Intent launchIntent = getIntentForComponent(TYPE_COMPONENT_FOREGROUND_SERVICE);
        mContext.startForegroundService(launchIntent);
        assertForegroundServiceState();
    }

    private Intent getIntentForComponent(int type) {
        final Intent intent = new Intent();
        if (type == TYPE_COMPONENT_ACTIVTIY) {
            intent.setComponent(new ComponentName(TEST_APP2_PKG, TEST_APP2_ACTIVITY_CLASS))
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        } else if (type == TYPE_COMPONENT_FOREGROUND_SERVICE) {
            intent.setComponent(new ComponentName(TEST_APP2_PKG, TEST_APP2_SERVICE_CLASS))
                    .setFlags(1);
        } else {
            fail("Unknown type: " + type);
        }
        return intent;
    }

    protected void stopForegroundService() throws Exception {
        executeShellCommand(String.format("am startservice -f 2 %s/%s",
                TEST_APP2_PKG, TEST_APP2_SERVICE_CLASS));
        // NOTE: cannot assert state because it depends on whether activity was on top before.
    }

    private Binder getNewNetworkStateObserver(final CountDownLatch latch,
            final String[] errors) {
        return new INetworkStateObserver.Stub() {
            @Override
            public boolean isForeground() {
                try {
                    final ProcessState state = getProcessStateByUid(mUid);
                    return !isBackground(state.state);
                } catch (Exception e) {
                    Log.d(TAG, "Error while reading the proc state for " + mUid + ": " + e);
                    return false;
                }
            }

            @Override
            public void onNetworkStateChecked(String resultData) {
                errors[0] = resultData == null
                        ? APP_NOT_FOREGROUND_ERROR
                        : checkForAvailabilityInResultData(resultData, true);
                latch.countDown();
            }
        };
    }

    /**
     * Finishes an activity on app2 so its process is demoted fromforeground status.
     */
    protected void finishActivity() throws Exception {
        executeShellCommand("am broadcast -a "
                + " com.android.cts.net.hostside.app2.action.FINISH_ACTIVITY "
                + "--receiver-foreground --receiver-registered-only");
    }

    protected void sendNotification(int notificationId, String notificationType) throws Exception {
        Log.d(TAG, "Sending notification broadcast (id=" + notificationId
                + ", type=" + notificationType);
        mServiceClient.sendNotification(notificationId, notificationType);
    }

    protected String showToast() {
        final Intent intent = new Intent(ACTION_SHOW_TOAST);
        intent.setPackage(TEST_APP2_PKG);
        Log.d(TAG, "Sending request to show toast");
        try {
            return sendOrderedBroadcast(intent, 3 * SECOND_IN_MS);
        } catch (Exception e) {
            return "";
        }
    }

    private ProcessState getProcessStateByUid(int uid) throws Exception {
        return new ProcessState(executeShellCommand("cmd activity get-uid-state " + uid));
    }

    private static class ProcessState {
        private final String fullState;
        final int state;

        ProcessState(String fullState) {
            this.fullState = fullState;
            try {
                this.state = Integer.parseInt(fullState.split(" ")[0]);
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not parse " + fullState);
            }
        }

        @Override
        public String toString() {
            return fullState;
        }
    }

    /**
     * Helper class used to assert the result of a Shell command.
     */
    protected static interface ExpectResultChecker {
        /**
         * Checkes whether the result of the command matched the expectation.
         */
        boolean isExpected(String result);
        /**
         * Gets the expected result so it's displayed on log and failure messages.
         */
        String getExpected();
    }
}
