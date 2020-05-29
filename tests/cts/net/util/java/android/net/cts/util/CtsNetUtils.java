/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.cts.util;

import static android.Manifest.permission.NETWORK_SETTINGS;
import static android.net.ConnectivityManager.PRIVATE_DNS_MODE_OPPORTUNISTIC;
import static android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET;
import static android.net.NetworkCapabilities.TRANSPORT_CELLULAR;
import static android.net.NetworkCapabilities.TRANSPORT_TEST;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.net.NetworkRequest;
import android.net.TestNetworkManager;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemProperties;
import android.provider.Settings;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.android.compatibility.common.util.SystemUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class CtsNetUtils {
    private static final String TAG = CtsNetUtils.class.getSimpleName();
    private static final int DURATION = 10000;
    private static final int SOCKET_TIMEOUT_MS = 2000;
    private static final int PRIVATE_DNS_PROBE_MS = 1_000;

    public static final int PRIVATE_DNS_SETTING_TIMEOUT_MS = 6_000;
    public static final int HTTP_PORT = 80;
    public static final String TEST_HOST = "connectivitycheck.gstatic.com";
    public static final String HTTP_REQUEST =
            "GET /generate_204 HTTP/1.0\r\n" +
                    "Host: " + TEST_HOST + "\r\n" +
                    "Connection: keep-alive\r\n\r\n";
    // Action sent to ConnectivityActionReceiver when a network callback is sent via PendingIntent.
    public static final String NETWORK_CALLBACK_ACTION =
            "ConnectivityManagerTest.NetworkCallbackAction";

    private final IBinder mBinder = new Binder();
    private final Context mContext;
    private final ConnectivityManager mCm;
    private final ContentResolver mCR;
    private final WifiManager mWifiManager;
    private TestNetworkCallback mCellNetworkCallback;
    private String mOldPrivateDnsMode;
    private String mOldPrivateDnsSpecifier;

    public CtsNetUtils(Context context) {
        mContext = context;
        mCm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mCR = context.getContentResolver();
    }

    /** Checks if FEATURE_IPSEC_TUNNELS is enabled on the device */
    public boolean hasIpsecTunnelsFeature() {
        return mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
                || SystemProperties.getInt("ro.product.first_api_level", 0)
                        >= Build.VERSION_CODES.Q;
    }

    /**
     * Sets the given appop using shell commands
     *
     * <p>Expects caller to hold the shell permission identity.
     */
    public void setAppopPrivileged(int appop, boolean allow) {
        final String opName = AppOpsManager.opToName(appop);
        for (final String pkg : new String[] {"com.android.shell", mContext.getPackageName()}) {
            final String cmd =
                    String.format(
                            "appops set %s %s %s",
                            pkg, // Package name
                            opName, // Appop
                            (allow ? "allow" : "deny")); // Action
            SystemUtil.runShellCommand(cmd);
        }
    }

    /** Sets up a test network using the provided interface name */
    public TestNetworkCallback setupAndGetTestNetwork(String ifname) throws Exception {
        // Build a network request
        final NetworkRequest nr =
                new NetworkRequest.Builder()
                        .clearCapabilities()
                        .addTransportType(TRANSPORT_TEST)
                        .setNetworkSpecifier(ifname)
                        .build();

        final TestNetworkCallback cb = new TestNetworkCallback();
        mCm.requestNetwork(nr, cb);

        // Setup the test network after network request is filed to prevent Network from being
        // reaped due to no requests matching it.
        mContext.getSystemService(TestNetworkManager.class).setupTestNetwork(ifname, mBinder);

        return cb;
    }

    // Toggle WiFi twice, leaving it in the state it started in
    public void toggleWifi() {
        if (mWifiManager.isWifiEnabled()) {
            Network wifiNetwork = getWifiNetwork();
            disconnectFromWifi(wifiNetwork);
            connectToWifi();
        } else {
            connectToWifi();
            Network wifiNetwork = getWifiNetwork();
            disconnectFromWifi(wifiNetwork);
        }
    }

    /** Enable WiFi and wait for it to become connected to a network. */
    public Network connectToWifi() {
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(makeWifiNetworkRequest(), callback);
        Network wifiNetwork = null;

        ConnectivityActionReceiver receiver = new ConnectivityActionReceiver(
                mCm, ConnectivityManager.TYPE_WIFI, NetworkInfo.State.CONNECTED);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(receiver, filter);

        boolean connected = false;
        try {
            SystemUtil.runShellCommand("svc wifi enable");
            SystemUtil.runWithShellPermissionIdentity(() -> mWifiManager.reconnect(),
                    NETWORK_SETTINGS);
            // Ensure we get both an onAvailable callback and a CONNECTIVITY_ACTION.
            wifiNetwork = callback.waitForAvailable();
            assertNotNull(wifiNetwork);
            connected = receiver.waitForState();
        } catch (InterruptedException ex) {
            fail("connectToWifi was interrupted");
        } finally {
            mCm.unregisterNetworkCallback(callback);
            mContext.unregisterReceiver(receiver);
        }

        assertTrue("Wifi must be configured to connect to an access point for this test.",
                connected);
        return wifiNetwork;
    }

    /** Disable WiFi and wait for it to become disconnected from the network. */
    public void disconnectFromWifi(Network wifiNetworkToCheck) {
        final TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(makeWifiNetworkRequest(), callback);
        Network lostWifiNetwork = null;

        ConnectivityActionReceiver receiver = new ConnectivityActionReceiver(
                mCm, ConnectivityManager.TYPE_WIFI, NetworkInfo.State.DISCONNECTED);
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(receiver, filter);

        // Assert that we can establish a TCP connection on wifi.
        Socket wifiBoundSocket = null;
        if (wifiNetworkToCheck != null) {
            try {
                wifiBoundSocket = getBoundSocket(wifiNetworkToCheck, TEST_HOST, HTTP_PORT);
                testHttpRequest(wifiBoundSocket);
            } catch (IOException e) {
                fail("HTTP request before wifi disconnected failed with: " + e);
            }
        }

        boolean disconnected = false;
        try {
            SystemUtil.runShellCommand("svc wifi disable");
            // Ensure we get both an onLost callback and a CONNECTIVITY_ACTION.
            lostWifiNetwork = callback.waitForLost();
            assertNotNull(lostWifiNetwork);
            disconnected = receiver.waitForState();
        } catch (InterruptedException ex) {
            fail("disconnectFromWifi was interrupted");
        } finally {
            mCm.unregisterNetworkCallback(callback);
            mContext.unregisterReceiver(receiver);
        }

        assertTrue("Wifi failed to reach DISCONNECTED state.", disconnected);

        // Check that the socket is closed when wifi disconnects.
        if (wifiBoundSocket != null) {
            try {
                testHttpRequest(wifiBoundSocket);
                fail("HTTP request should not succeed after wifi disconnects");
            } catch (IOException expected) {
                assertEquals(Os.strerror(OsConstants.ECONNABORTED), expected.getMessage());
            }
        }
    }

    public Network getWifiNetwork() {
        TestNetworkCallback callback = new TestNetworkCallback();
        mCm.registerNetworkCallback(makeWifiNetworkRequest(), callback);
        Network network = null;
        try {
            network = callback.waitForAvailable();
        } catch (InterruptedException e) {
            fail("NetworkCallback wait was interrupted.");
        } finally {
            mCm.unregisterNetworkCallback(callback);
        }
        assertNotNull("Cannot find Network for wifi. Is wifi connected?", network);
        return network;
    }

    public Network connectToCell() throws InterruptedException {
        if (cellConnectAttempted()) {
            throw new IllegalStateException("Already connected");
        }
        NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build();
        mCellNetworkCallback = new TestNetworkCallback();
        mCm.requestNetwork(cellRequest, mCellNetworkCallback);
        final Network cellNetwork = mCellNetworkCallback.waitForAvailable();
        assertNotNull("Cell network not available. " +
                "Please ensure the device has working mobile data.", cellNetwork);
        return cellNetwork;
    }

    public void disconnectFromCell() {
        if (!cellConnectAttempted()) {
            throw new IllegalStateException("Cell connection not attempted");
        }
        mCm.unregisterNetworkCallback(mCellNetworkCallback);
        mCellNetworkCallback = null;
    }

    public boolean cellConnectAttempted() {
        return mCellNetworkCallback != null;
    }

    private NetworkRequest makeWifiNetworkRequest() {
        return new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
    }

    private void testHttpRequest(Socket s) throws IOException {
        OutputStream out = s.getOutputStream();
        InputStream in = s.getInputStream();

        final byte[] requestBytes = HTTP_REQUEST.getBytes("UTF-8");
        byte[] responseBytes = new byte[4096];
        out.write(requestBytes);
        in.read(responseBytes);
        assertTrue(new String(responseBytes, "UTF-8").startsWith("HTTP/1.0 204 No Content\r\n"));
    }

    private Socket getBoundSocket(Network network, String host, int port) throws IOException {
        InetSocketAddress addr = new InetSocketAddress(host, port);
        Socket s = network.getSocketFactory().createSocket();
        try {
            s.setSoTimeout(SOCKET_TIMEOUT_MS);
            s.connect(addr, SOCKET_TIMEOUT_MS);
        } catch (IOException e) {
            s.close();
            throw e;
        }
        return s;
    }

    public void storePrivateDnsSetting() {
        // Store private DNS setting
        mOldPrivateDnsMode = Settings.Global.getString(mCR, Settings.Global.PRIVATE_DNS_MODE);
        mOldPrivateDnsSpecifier = Settings.Global.getString(mCR,
                Settings.Global.PRIVATE_DNS_SPECIFIER);
        // It's possible that there is no private DNS default value in Settings.
        // Give it a proper default mode which is opportunistic mode.
        if (mOldPrivateDnsMode == null) {
            mOldPrivateDnsSpecifier = "";
            mOldPrivateDnsMode = PRIVATE_DNS_MODE_OPPORTUNISTIC;
            Settings.Global.putString(mCR,
                    Settings.Global.PRIVATE_DNS_SPECIFIER, mOldPrivateDnsSpecifier);
            Settings.Global.putString(mCR, Settings.Global.PRIVATE_DNS_MODE, mOldPrivateDnsMode);
        }
    }

    public void restorePrivateDnsSetting() throws InterruptedException {
        if (mOldPrivateDnsMode == null || mOldPrivateDnsSpecifier == null) {
            return;
        }
        // restore private DNS setting
        if ("hostname".equals(mOldPrivateDnsMode)) {
            setPrivateDnsStrictMode(mOldPrivateDnsSpecifier);
            awaitPrivateDnsSetting("restorePrivateDnsSetting timeout",
                    mCm.getActiveNetwork(),
                    mOldPrivateDnsSpecifier, true);
        } else {
            Settings.Global.putString(mCR, Settings.Global.PRIVATE_DNS_MODE, mOldPrivateDnsMode);
        }
    }

    public void setPrivateDnsStrictMode(String server) {
        // To reduce flake rate, set PRIVATE_DNS_SPECIFIER before PRIVATE_DNS_MODE. This ensures
        // that if the previous private DNS mode was not "hostname", the system only sees one
        // EVENT_PRIVATE_DNS_SETTINGS_CHANGED event instead of two.
        Settings.Global.putString(mCR, Settings.Global.PRIVATE_DNS_SPECIFIER, server);
        final String mode = Settings.Global.getString(mCR, Settings.Global.PRIVATE_DNS_MODE);
        // If current private DNS mode is "hostname", we only need to set PRIVATE_DNS_SPECIFIER.
        if (!"hostname".equals(mode)) {
            Settings.Global.putString(mCR, Settings.Global.PRIVATE_DNS_MODE, "hostname");
        }
    }

    public void awaitPrivateDnsSetting(@NonNull String msg, @NonNull Network network,
            @NonNull String server, boolean requiresValidatedServers) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        NetworkRequest request = new NetworkRequest.Builder().clearCapabilities().build();
        NetworkCallback callback = new NetworkCallback() {
            @Override
            public void onLinkPropertiesChanged(Network n, LinkProperties lp) {
                if (requiresValidatedServers && lp.getValidatedPrivateDnsServers().isEmpty()) {
                    return;
                }
                if (network.equals(n) && server.equals(lp.getPrivateDnsServerName())) {
                    latch.countDown();
                }
            }
        };
        mCm.registerNetworkCallback(request, callback);
        assertTrue(msg, latch.await(PRIVATE_DNS_SETTING_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        mCm.unregisterNetworkCallback(callback);
        // Wait some time for NetworkMonitor's private DNS probe to complete. If we do not do
        // this, then the test could complete before the NetworkMonitor private DNS probe
        // completes. This would result in tearDown disabling private DNS, and the NetworkMonitor
        // private DNS probe getting stuck because there are no longer any private DNS servers to
        // query. This then results in the next test not being able to change the private DNS
        // setting within the timeout, because the NetworkMonitor thread is blocked in the
        // private DNS probe. There is no way to know when the probe has completed: because the
        // network is likely already validated, there is no callback that we can listen to, so
        // just sleep.
        if (requiresValidatedServers) {
            Thread.sleep(PRIVATE_DNS_PROBE_MS);
        }
    }

    /**
     * Receiver that captures the last connectivity change's network type and state. Recognizes
     * both {@code CONNECTIVITY_ACTION} and {@code NETWORK_CALLBACK_ACTION} intents.
     */
    public static class ConnectivityActionReceiver extends BroadcastReceiver {

        private final CountDownLatch mReceiveLatch = new CountDownLatch(1);

        private final int mNetworkType;
        private final NetworkInfo.State mNetState;
        private final ConnectivityManager mCm;

        public ConnectivityActionReceiver(ConnectivityManager cm, int networkType,
                NetworkInfo.State netState) {
            this.mCm = cm;
            mNetworkType = networkType;
            mNetState = netState;
        }

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            NetworkInfo networkInfo = null;

            // When receiving ConnectivityManager.CONNECTIVITY_ACTION, the NetworkInfo parcelable
            // is stored in EXTRA_NETWORK_INFO. With a NETWORK_CALLBACK_ACTION, the Network is
            // sent in EXTRA_NETWORK and we need to ask the ConnectivityManager for the NetworkInfo.
            if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action)) {
                networkInfo = intent.getExtras()
                        .getParcelable(ConnectivityManager.EXTRA_NETWORK_INFO);
                assertNotNull("ConnectivityActionReceiver expected EXTRA_NETWORK_INFO",
                        networkInfo);
            } else if (NETWORK_CALLBACK_ACTION.equals(action)) {
                Network network = intent.getExtras()
                        .getParcelable(ConnectivityManager.EXTRA_NETWORK);
                assertNotNull("ConnectivityActionReceiver expected EXTRA_NETWORK", network);
                networkInfo = this.mCm.getNetworkInfo(network);
                if (networkInfo == null) {
                    // When disconnecting, it seems like we get an intent sent with an invalid
                    // Network; that is, by the time we call ConnectivityManager.getNetworkInfo(),
                    // it is invalid. Ignore these.
                    Log.i(TAG, "ConnectivityActionReceiver NETWORK_CALLBACK_ACTION ignoring "
                            + "invalid network");
                    return;
                }
            } else {
                fail("ConnectivityActionReceiver received unxpected intent action: " + action);
            }

            assertNotNull("ConnectivityActionReceiver didn't find NetworkInfo", networkInfo);
            int networkType = networkInfo.getType();
            State networkState = networkInfo.getState();
            Log.i(TAG, "Network type: " + networkType + " state: " + networkState);
            if (networkType == mNetworkType && networkInfo.getState() == mNetState) {
                mReceiveLatch.countDown();
            }
        }

        public boolean waitForState() throws InterruptedException {
            return mReceiveLatch.await(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Callback used in testRegisterNetworkCallback that allows caller to block on
     * {@code onAvailable}.
     */
    public static class TestNetworkCallback extends ConnectivityManager.NetworkCallback {
        private final CountDownLatch mAvailableLatch = new CountDownLatch(1);
        private final CountDownLatch mLostLatch = new CountDownLatch(1);
        private final CountDownLatch mUnavailableLatch = new CountDownLatch(1);

        public Network currentNetwork;
        public Network lastLostNetwork;

        public Network waitForAvailable() throws InterruptedException {
            return mAvailableLatch.await(30, TimeUnit.SECONDS) ? currentNetwork : null;
        }

        public Network waitForLost() throws InterruptedException {
            return mLostLatch.await(30, TimeUnit.SECONDS) ? lastLostNetwork : null;
        }

        public boolean waitForUnavailable() throws InterruptedException {
            return mUnavailableLatch.await(2, TimeUnit.SECONDS);
        }


        @Override
        public void onAvailable(Network network) {
            currentNetwork = network;
            mAvailableLatch.countDown();
        }

        @Override
        public void onLost(Network network) {
            lastLostNetwork = network;
            if (network.equals(currentNetwork)) {
                currentNetwork = null;
            }
            mLostLatch.countDown();
        }

        @Override
        public void onUnavailable() {
            mUnavailableLatch.countDown();
        }
    }
}
