/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net;

import static android.Manifest.permission.MANAGE_TEST_NETWORKS;
import static android.Manifest.permission.NETWORK_SETTINGS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.net.EthernetManager.TetheredInterfaceCallback;
import android.net.EthernetManager.TetheredInterfaceRequest;
import android.net.TetheringManager.StartTetheringCallback;
import android.net.TetheringManager.TetheringEventCallback;
import android.net.TetheringManager.TetheringRequest;
import android.net.dhcp.DhcpAckPacket;
import android.net.dhcp.DhcpOfferPacket;
import android.net.dhcp.DhcpPacket;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.system.Os;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.testutils.HandlerUtilsKt;
import com.android.testutils.TapPacketReader;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileDescriptor;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@MediumTest
public class EthernetTetheringTest {

    private static final String TAG = EthernetTetheringTest.class.getSimpleName();
    private static final int TIMEOUT_MS = 1000;
    private static final int PACKET_READ_TIMEOUT_MS = 100;
    private static final int DHCP_DISCOVER_ATTEMPTS = 10;
    private static final byte[] DHCP_REQUESTED_PARAMS = new byte[] {
            DhcpPacket.DHCP_SUBNET_MASK,
            DhcpPacket.DHCP_ROUTER,
            DhcpPacket.DHCP_DNS_SERVER,
            DhcpPacket.DHCP_LEASE_TIME,
    };
    private static final String DHCP_HOSTNAME = "testhostname";

    private final Context mContext = InstrumentationRegistry.getContext();
    private final EthernetManager mEm = mContext.getSystemService(EthernetManager.class);
    private final TetheringManager mTm = mContext.getSystemService(TetheringManager.class);

    private TestNetworkInterface mTestIface;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private TapPacketReader mTapPacketReader;

    private TetheredInterfaceRequester mTetheredInterfaceRequester;
    private MyTetheringEventCallback mTetheringEventCallback;

    private UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    @Before
    public void setUp() throws Exception {
        mHandlerThread = new HandlerThread(getClass().getSimpleName());
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mTetheredInterfaceRequester = new TetheredInterfaceRequester(mHandler, mEm);
        // Needed to create a TestNetworkInterface, to call requestTetheredInterface, and to receive
        // tethered client callbacks.
        mUiAutomation.adoptShellPermissionIdentity(MANAGE_TEST_NETWORKS, NETWORK_SETTINGS);
    }

    private void cleanUp() throws Exception {
        mTm.stopTethering(TetheringManager.TETHERING_ETHERNET);
        if (mTetheringEventCallback != null) {
            mTetheringEventCallback.awaitInterfaceUntethered();
            mTetheringEventCallback.unregister();
            mTetheringEventCallback = null;
        }
        if (mTapPacketReader != null) {
            TapPacketReader reader = mTapPacketReader;
            mHandler.post(() -> reader.stop());
            mTapPacketReader = null;
        }
        mHandlerThread.quitSafely();
        mTetheredInterfaceRequester.release();
        mEm.setIncludeTestInterfaces(false);
        maybeDeleteTestInterface();
    }

    @After
    public void tearDown() throws Exception {
        try {
            cleanUp();
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    public void testVirtualEthernetAlreadyExists() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(mEm.isAvailable());

        mTestIface = createTestInterface();
        // This must be done now because as soon as setIncludeTestInterfaces(true) is called, the
        // interface will be placed in client mode, which will delete the link-local address.
        // At that point NetworkInterface.getByName() will cease to work on the interface, because
        // starting in R NetworkInterface can no longer see interfaces without IP addresses.
        int mtu = getMTU(mTestIface);

        Log.d(TAG, "Including test interfaces");
        mEm.setIncludeTestInterfaces(true);

        Log.d(TAG, "Requesting tethered interface");
        mTetheredInterfaceRequester.requestInterface();

        final String iface = mTetheredInterfaceRequester.awaitRequestedInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mTestIface.getInterfaceName(), iface);

        checkVirtualEthernet(mTestIface, mtu);
    }

    @Test
    public void testVirtualEthernet() throws Exception {
        // This test requires manipulating packets. Skip if there is a physical Ethernet connected.
        assumeFalse(mEm.isAvailable());

        Log.d(TAG, "Requesting tethered interface");
        mTetheredInterfaceRequester.requestInterface();

        mEm.setIncludeTestInterfaces(true);

        mTestIface = createTestInterface();

        final String iface = mTetheredInterfaceRequester.awaitRequestedInterface();
        assertEquals("TetheredInterfaceCallback for unexpected interface",
                mTestIface.getInterfaceName(), iface);

        checkVirtualEthernet(mTestIface, getMTU(mTestIface));
    }

    @Test
    public void testPhysicalEthernet() throws Exception {
        assumeTrue(mEm.isAvailable());

        // Get an interface to use.
        mTetheredInterfaceRequester.requestInterface();
        String iface = mTetheredInterfaceRequester.awaitRequestedInterface();

        // Enable Ethernet tethering and check that it starts.
        mTetheringEventCallback = enableEthernetTethering(iface);

        // There is nothing more we can do on a physical interface without connecting an actual
        // client, which is not possible in this test.
    }

    private static final class MyTetheringEventCallback implements TetheringEventCallback {
        private final TetheringManager mTm;
        private final CountDownLatch mTetheringStartedLatch = new CountDownLatch(1);
        private final CountDownLatch mTetheringStoppedLatch = new CountDownLatch(1);
        private final CountDownLatch mClientConnectedLatch = new CountDownLatch(1);
        private final String mIface;

        private volatile boolean mInterfaceWasTethered = false;
        private volatile boolean mUnregistered = false;
        private volatile Collection<TetheredClient> mClients = null;

        MyTetheringEventCallback(TetheringManager tm, String iface) {
            mTm = tm;
            mIface = iface;
        }

        public void unregister() {
            mTm.unregisterTetheringEventCallback(this);
            mUnregistered = true;
        }

        @Override
        public void onTetheredInterfacesChanged(List<String> interfaces) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            final boolean wasTethered = mTetheringStartedLatch.getCount() == 0;
            if (!mInterfaceWasTethered && (mIface == null || interfaces.contains(mIface))) {
                // This interface is being tethered for the first time.
                Log.d(TAG, "Tethering started: " + interfaces);
                mInterfaceWasTethered = true;
                mTetheringStartedLatch.countDown();
            } else if (mInterfaceWasTethered && !interfaces.contains(mIface)) {
                Log.d(TAG, "Tethering stopped: " + interfaces);
                mTetheringStoppedLatch.countDown();
            }
        }

        public void awaitInterfaceTethered() throws Exception {
            assertTrue("Ethernet not tethered after " + TIMEOUT_MS + "ms",
                    mTetheringStartedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        public void awaitInterfaceUntethered() throws Exception {
            // Don't block teardown if the interface was never tethered.
            // This is racy because the interface might become tethered right after this check, but
            // that can only happen in tearDown if startTethering timed out, which likely means
            // the test has already failed.
            if (!mInterfaceWasTethered) return;

            assertTrue(mIface + " not untethered after " + TIMEOUT_MS + "ms",
                    mTetheringStoppedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        }

        @Override
        public void onError(String ifName, int error) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            fail("TetheringEventCallback got error:" + error + " on iface " + ifName);
        }

        @Override
        public void onClientsChanged(Collection<TetheredClient> clients) {
            // Ignore stale callbacks registered by previous test cases.
            if (mUnregistered) return;

            Log.d(TAG, "Got clients changed: " + clients);
            mClients = clients;
            if (clients.size() > 0) {
                mClientConnectedLatch.countDown();
            }
        }

        public Collection<TetheredClient> awaitClientConnected() throws Exception {
            assertTrue("Did not receive client connected callback after " + TIMEOUT_MS + "ms",
                    mClientConnectedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return mClients;
        }
    }

    private MyTetheringEventCallback enableEthernetTethering(String iface) throws Exception {
        MyTetheringEventCallback callback = new MyTetheringEventCallback(mTm, iface);
        mTm.registerTetheringEventCallback(mHandler::post, callback);

        StartTetheringCallback startTetheringCallback = new StartTetheringCallback() {
            @Override
            public void onTetheringFailed(int resultCode) {
                fail("Unexpectedly got onTetheringFailed");
            }
        };
        Log.d(TAG, "Starting Ethernet tethering");
        mTm.startTethering(
                new TetheringRequest.Builder(TetheringManager.TETHERING_ETHERNET).build(),
                mHandler::post /* executor */,  startTetheringCallback);
        callback.awaitInterfaceTethered();
        return callback;
    }

    private int getMTU(TestNetworkInterface iface) throws SocketException {
        NetworkInterface nif = NetworkInterface.getByName(iface.getInterfaceName());
        assertNotNull("Can't get NetworkInterface object for " + iface.getInterfaceName(), nif);
        return nif.getMTU();
    }

    private void checkVirtualEthernet(TestNetworkInterface iface, int mtu) throws Exception {
        FileDescriptor fd = iface.getFileDescriptor().getFileDescriptor();
        mTapPacketReader = new TapPacketReader(mHandler, fd, mtu);
        mHandler.post(() -> mTapPacketReader.start());
        HandlerUtilsKt.waitForIdle(mHandler, TIMEOUT_MS);

        mTetheringEventCallback = enableEthernetTethering(iface.getInterfaceName());
        checkTetheredClientCallbacks(fd);
    }

    private void checkTetheredClientCallbacks(FileDescriptor fd) throws Exception {
        // Create a fake client.
        byte[] clientMacAddr = new byte[6];
        new Random().nextBytes(clientMacAddr);

        // We have to retransmit DHCP requests because IpServer declares itself to be ready before
        // its DhcpServer is actually started. TODO: fix this race and remove this loop.
        DhcpPacket offerPacket = null;
        for (int i = 0; i < DHCP_DISCOVER_ATTEMPTS; i++) {
            Log.d(TAG, "Sending DHCP discover");
            sendDhcpDiscover(fd, clientMacAddr);
            offerPacket = getNextDhcpPacket();
            if (offerPacket instanceof DhcpOfferPacket) break;
        }
        assertTrue("No DHCPOFFER received on interface within timeout",
                offerPacket instanceof DhcpOfferPacket);

        sendDhcpRequest(fd, offerPacket, clientMacAddr);
        DhcpPacket ackPacket = getNextDhcpPacket();
        assertTrue("No DHCPACK received on interface within timeout",
                ackPacket instanceof DhcpAckPacket);

        final Collection<TetheredClient> clients = mTetheringEventCallback.awaitClientConnected();
        assertEquals(1, clients.size());
        final TetheredClient client = clients.iterator().next();

        // Check the MAC address.
        assertEquals(MacAddress.fromBytes(clientMacAddr), client.getMacAddress());
        assertEquals(TetheringManager.TETHERING_ETHERNET, client.getTetheringType());

        // Check the hostname.
        assertEquals(1, client.getAddresses().size());
        TetheredClient.AddressInfo info = client.getAddresses().get(0);
        assertEquals(DHCP_HOSTNAME, info.getHostname());

        // Check the address is the one that was handed out in the DHCP ACK.
        DhcpResults dhcpResults = offerPacket.toDhcpResults();
        assertLinkAddressMatches(dhcpResults.ipAddress, info.getAddress());

        // Check that the lifetime is correct +/- 10s.
        final long now = SystemClock.elapsedRealtime();
        final long actualLeaseDuration = (info.getAddress().getExpirationTime() - now) / 1000;
        final String msg = String.format("IP address should have lifetime of %d, got %d",
                dhcpResults.leaseDuration, actualLeaseDuration);
        assertTrue(msg, Math.abs(dhcpResults.leaseDuration - actualLeaseDuration) < 10);
    }

    private DhcpPacket getNextDhcpPacket() throws ParseException {
        byte[] packet;
        while ((packet = mTapPacketReader.popPacket(PACKET_READ_TIMEOUT_MS)) != null) {
            try {
                return DhcpPacket.decodeFullPacket(packet, packet.length, DhcpPacket.ENCAP_L2);
            } catch (DhcpPacket.ParseException e) {
                // Not a DHCP packet. Continue.
            }
        }
        return null;
    }

    private static final class TetheredInterfaceRequester implements TetheredInterfaceCallback {
        private final CountDownLatch mInterfaceAvailableLatch = new CountDownLatch(1);
        private final Handler mHandler;
        private final EthernetManager mEm;

        private volatile TetheredInterfaceRequest mRequest;
        private volatile String mIface;

        TetheredInterfaceRequester(Handler handler, EthernetManager em) {
            mHandler = handler;
            mEm = em;
        }

        @Override
        public void onAvailable(String iface) {
            Log.d(TAG, "Ethernet interface available: " + iface);
            mIface = iface;
            mInterfaceAvailableLatch.countDown();
        }
        @Override
        public void onUnavailable() {}

        public void requestInterface() {
            assertNull("BUG: more than one tethered interface request", mRequest);
            mRequest = mEm.requestTetheredInterface(mHandler::post, this);
        }

        public String awaitRequestedInterface() throws InterruptedException {
            assertTrue("No tethered interface available after " + TIMEOUT_MS + "ms",
                    mInterfaceAvailableLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
            return mIface;
        }

        public void release() {
            if (mRequest != null) {
                mRequest.release();
                mRequest = null;
            }
        }
    }

    private void sendDhcpDiscover(FileDescriptor fd, byte[] macAddress) throws Exception {
        ByteBuffer packet = DhcpPacket.buildDiscoverPacket(DhcpPacket.ENCAP_L2,
                new Random().nextInt() /* transactionId */, (short) 0 /* secs */,
                macAddress,  false /* unicast */, DHCP_REQUESTED_PARAMS,
                false /* rapid commit */,  DHCP_HOSTNAME);
        sendPacket(fd, packet);
    }

    private void sendDhcpRequest(FileDescriptor fd, DhcpPacket offerPacket, byte[] macAddress)
            throws Exception {
        DhcpResults results = offerPacket.toDhcpResults();
        Inet4Address clientIp = (Inet4Address) results.ipAddress.getAddress();
        Inet4Address serverIdentifier = results.serverAddress;
        ByteBuffer packet = DhcpPacket.buildRequestPacket(DhcpPacket.ENCAP_L2,
                0 /* transactionId */, (short) 0 /* secs */, DhcpPacket.INADDR_ANY /* clientIp */,
                false /* broadcast */, macAddress, clientIp /* requestedIpAddress */,
                serverIdentifier, DHCP_REQUESTED_PARAMS, DHCP_HOSTNAME);
        sendPacket(fd, packet);
    }

    private void sendPacket(FileDescriptor fd, ByteBuffer packet) throws Exception {
        assertNotNull("Only tests on virtual interfaces can send packets", fd);
        Os.write(fd, packet);
    }

    public void assertLinkAddressMatches(LinkAddress l1, LinkAddress l2) {
        // Check all fields except the deprecation and expiry times.
        String msg = String.format("LinkAddresses do not match. expected: %s actual: %s", l1, l2);
        assertTrue(msg, l1.isSameAddressAs(l2));
        assertEquals("LinkAddress flags do not match", l1.getFlags(), l2.getFlags());
        assertEquals("LinkAddress scope does not match", l1.getScope(), l2.getScope());
    }

    private TestNetworkInterface createTestInterface() throws Exception {
        TestNetworkManager tnm = mContext.getSystemService(TestNetworkManager.class);
        TestNetworkInterface iface = tnm.createTapInterface();
        Log.d(TAG, "Created test interface " + iface.getInterfaceName());
        assertNotNull(NetworkInterface.getByName(iface.getInterfaceName()));
        return iface;
    }

    private void maybeDeleteTestInterface() throws Exception {
        if (mTestIface != null) {
            mTestIface.getFileDescriptor().close();
            Log.d(TAG, "Deleted test interface " + mTestIface.getInterfaceName());
            mTestIface = null;
        }
    }
}
