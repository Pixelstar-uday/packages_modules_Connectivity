/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import static android.net.InetAddresses.parseNumericAddress;
import static android.net.nsd.NsdManager.FAILURE_BAD_PARAMETERS;
import static android.net.nsd.NsdManager.FAILURE_INTERNAL_ERROR;
import static android.net.nsd.NsdManager.FAILURE_OPERATION_NOT_RUNNING;

import static com.android.server.NsdService.constructServiceType;
import static com.android.testutils.ContextUtils.mockService;

import static libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import static libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.compat.testing.PlatformCompatChangeRule;
import android.content.ContentResolver;
import android.content.Context;
import android.net.INetd;
import android.net.Network;
import android.net.connectivity.ConnectivityCompatChanges;
import android.net.mdns.aidl.DiscoveryInfo;
import android.net.mdns.aidl.GetAddressInfo;
import android.net.mdns.aidl.IMDnsEventListener;
import android.net.mdns.aidl.RegistrationInfo;
import android.net.mdns.aidl.ResolutionInfo;
import android.net.nsd.INsdManagerCallback;
import android.net.nsd.INsdServiceConnector;
import android.net.nsd.MDnsManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdManager.DiscoveryListener;
import android.net.nsd.NsdManager.RegistrationListener;
import android.net.nsd.NsdManager.ResolveListener;
import android.net.nsd.NsdServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.server.NsdService.Dependencies;
import com.android.server.connectivity.mdns.MdnsAdvertiser;
import com.android.server.connectivity.mdns.MdnsDiscoveryManager;
import com.android.server.connectivity.mdns.MdnsServiceBrowserListener;
import com.android.server.connectivity.mdns.MdnsServiceInfo;
import com.android.server.connectivity.mdns.MdnsSocketProvider;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;
import com.android.testutils.HandlerUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

// TODOs:
//  - test client can send requests and receive replies
//  - test NSD_ON ENABLE/DISABLED listening
@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S_V2)
public class NsdServiceTest {
    static final int PROTOCOL = NsdManager.PROTOCOL_DNS_SD;
    private static final long CLEANUP_DELAY_MS = 500;
    private static final long TIMEOUT_MS = 500;
    private static final String SERVICE_NAME = "a_name";
    private static final String SERVICE_TYPE = "_test._tcp";
    private static final String SERVICE_FULL_NAME = SERVICE_NAME + "." + SERVICE_TYPE;
    private static final String DOMAIN_NAME = "mytestdevice.local";
    private static final int PORT = 2201;
    private static final int IFACE_IDX_ANY = 0;
    private static final String IPV4_ADDRESS = "192.0.2.0";
    private static final String IPV6_ADDRESS = "2001:db8::";

    // Records INsdManagerCallback created when NsdService#connect is called.
    // Only accessed on the test thread, since NsdService#connect is called by the NsdManager
    // constructor called on the test thread.
    private final Queue<INsdManagerCallback> mCreatedCallbacks = new LinkedList<>();

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();
    @Mock Context mContext;
    @Mock ContentResolver mResolver;
    @Mock MDnsManager mMockMDnsM;
    @Mock Dependencies mDeps;
    @Mock MdnsDiscoveryManager mDiscoveryManager;
    @Mock MdnsAdvertiser mAdvertiser;
    @Mock MdnsSocketProvider mSocketProvider;
    HandlerThread mThread;
    TestHandler mHandler;
    NsdService mService;

    private static class LinkToDeathRecorder extends Binder {
        IBinder.DeathRecipient mDr;

        @Override
        public void linkToDeath(@NonNull DeathRecipient recipient, int flags) {
            super.linkToDeath(recipient, flags);
            mDr = recipient;
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mThread = new HandlerThread("mock-service-handler");
        mThread.start();
        mHandler = new TestHandler(mThread.getLooper());
        when(mContext.getContentResolver()).thenReturn(mResolver);
        mockService(mContext, MDnsManager.class, MDnsManager.MDNS_SERVICE, mMockMDnsM);
        if (mContext.getSystemService(MDnsManager.class) == null) {
            // Test is using mockito-extended
            doCallRealMethod().when(mContext).getSystemService(MDnsManager.class);
        }
        doReturn(true).when(mMockMDnsM).registerService(
                anyInt(), anyString(), anyString(), anyInt(), any(), anyInt());
        doReturn(true).when(mMockMDnsM).stopOperation(anyInt());
        doReturn(true).when(mMockMDnsM).discover(anyInt(), anyString(), anyInt());
        doReturn(true).when(mMockMDnsM).resolve(
                anyInt(), anyString(), anyString(), anyString(), anyInt());
        doReturn(false).when(mDeps).isMdnsDiscoveryManagerEnabled(any(Context.class));
        doReturn(mDiscoveryManager).when(mDeps).makeMdnsDiscoveryManager(any(), any());
        doReturn(mSocketProvider).when(mDeps).makeMdnsSocketProvider(any(), any());
        doReturn(mAdvertiser).when(mDeps).makeMdnsAdvertiser(any(), any(), any());

        mService = makeService();
    }

    @After
    public void tearDown() throws Exception {
        if (mThread != null) {
            mThread.quit();
            mThread = null;
        }
    }

    @Test
    @DisableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testPreSClients() throws Exception {
        // Pre S client connected, the daemon should be started.
        connectClient(mService);
        final INsdManagerCallback cb1 = getCallback();
        final IBinder.DeathRecipient deathRecipient1 = verifyLinkToDeath(cb1);
        verify(mMockMDnsM, times(1)).registerEventListener(any());
        verify(mMockMDnsM, times(1)).startDaemon();

        connectClient(mService);
        final INsdManagerCallback cb2 = getCallback();
        final IBinder.DeathRecipient deathRecipient2 = verifyLinkToDeath(cb2);
        // Daemon has been started, it should not try to start it again.
        verify(mMockMDnsM, times(1)).registerEventListener(any());
        verify(mMockMDnsM, times(1)).startDaemon();

        deathRecipient1.binderDied();
        // Still 1 client remains, daemon shouldn't be stopped.
        waitForIdle();
        verify(mMockMDnsM, never()).stopDaemon();

        deathRecipient2.binderDied();
        // All clients are disconnected, the daemon should be stopped.
        verifyDelayMaybeStopDaemon(CLEANUP_DELAY_MS);
    }

    @Test
    @EnableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testNoDaemonStartedWhenClientsConnect() throws Exception {
        // Creating an NsdManager will not cause daemon startup.
        connectClient(mService);
        verify(mMockMDnsM, never()).registerEventListener(any());
        verify(mMockMDnsM, never()).startDaemon();
        final INsdManagerCallback cb1 = getCallback();
        final IBinder.DeathRecipient deathRecipient1 = verifyLinkToDeath(cb1);

        // Creating another NsdManager will not cause daemon startup either.
        connectClient(mService);
        verify(mMockMDnsM, never()).registerEventListener(any());
        verify(mMockMDnsM, never()).startDaemon();
        final INsdManagerCallback cb2 = getCallback();
        final IBinder.DeathRecipient deathRecipient2 = verifyLinkToDeath(cb2);

        // If there is no active request, try to clean up the daemon but should not do it because
        // daemon has not been started.
        deathRecipient1.binderDied();
        verify(mMockMDnsM, never()).unregisterEventListener(any());
        verify(mMockMDnsM, never()).stopDaemon();
        deathRecipient2.binderDied();
        verify(mMockMDnsM, never()).unregisterEventListener(any());
        verify(mMockMDnsM, never()).stopDaemon();
    }

    private IBinder.DeathRecipient verifyLinkToDeath(INsdManagerCallback cb)
            throws Exception {
        final IBinder.DeathRecipient dr = ((LinkToDeathRecorder) cb.asBinder()).mDr;
        assertNotNull(dr);
        return dr;
    }

    @Test
    @EnableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testClientRequestsAreGCedAtDisconnection() throws Exception {
        final NsdManager client = connectClient(mService);
        final INsdManagerCallback cb1 = getCallback();
        final IBinder.DeathRecipient deathRecipient = verifyLinkToDeath(cb1);
        verify(mMockMDnsM, never()).registerEventListener(any());
        verify(mMockMDnsM, never()).startDaemon();

        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        request.setPort(PORT);

        // Client registration request
        final RegistrationListener listener1 = mock(RegistrationListener.class);
        client.registerService(request, PROTOCOL, listener1);
        waitForIdle();
        verify(mMockMDnsM).registerEventListener(any());
        verify(mMockMDnsM).startDaemon();
        verify(mMockMDnsM).registerService(
                eq(2), eq(SERVICE_NAME), eq(SERVICE_TYPE), eq(PORT), any(), eq(IFACE_IDX_ANY));

        // Client discovery request
        final DiscoveryListener listener2 = mock(DiscoveryListener.class);
        client.discoverServices(SERVICE_TYPE, PROTOCOL, listener2);
        waitForIdle();
        verify(mMockMDnsM).discover(3 /* id */, SERVICE_TYPE, IFACE_IDX_ANY);

        // Client resolve request
        final ResolveListener listener3 = mock(ResolveListener.class);
        client.resolveService(request, listener3);
        waitForIdle();
        verify(mMockMDnsM).resolve(
                4 /* id */, SERVICE_NAME, SERVICE_TYPE, "local." /* domain */, IFACE_IDX_ANY);

        // Client disconnects, stop the daemon after CLEANUP_DELAY_MS.
        deathRecipient.binderDied();
        verifyDelayMaybeStopDaemon(CLEANUP_DELAY_MS);
        // checks that request are cleaned
        verify(mMockMDnsM).stopOperation(2 /* id */);
        verify(mMockMDnsM).stopOperation(3 /* id */);
        verify(mMockMDnsM).stopOperation(4 /* id */);
    }

    @Test
    @EnableCompatChanges(ConnectivityCompatChanges.RUN_NATIVE_NSD_ONLY_IF_LEGACY_APPS_T_AND_LATER)
    public void testCleanupDelayNoRequestActive() throws Exception {
        final NsdManager client = connectClient(mService);

        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        request.setPort(PORT);
        final RegistrationListener listener1 = mock(RegistrationListener.class);
        client.registerService(request, PROTOCOL, listener1);
        waitForIdle();
        verify(mMockMDnsM).registerEventListener(any());
        verify(mMockMDnsM).startDaemon();
        final INsdManagerCallback cb1 = getCallback();
        final IBinder.DeathRecipient deathRecipient = verifyLinkToDeath(cb1);
        verify(mMockMDnsM).registerService(
                eq(2), eq(SERVICE_NAME), eq(SERVICE_TYPE), eq(PORT), any(), eq(IFACE_IDX_ANY));

        client.unregisterService(listener1);
        waitForIdle();
        verify(mMockMDnsM).stopOperation(2 /* id */);

        verifyDelayMaybeStopDaemon(CLEANUP_DELAY_MS);
        reset(mMockMDnsM);
        deathRecipient.binderDied();
        // Client disconnects, daemon should not be stopped after CLEANUP_DELAY_MS.
        verify(mMockMDnsM, never()).unregisterEventListener(any());
        verify(mMockMDnsM, never()).stopDaemon();
    }

    private IMDnsEventListener getEventListener() {
        final ArgumentCaptor<IMDnsEventListener> listenerCaptor =
                ArgumentCaptor.forClass(IMDnsEventListener.class);
        verify(mMockMDnsM).registerEventListener(listenerCaptor.capture());
        return listenerCaptor.getValue();
    }

    @Test
    public void testDiscoverOnTetheringDownstream() throws Exception {
        final NsdManager client = connectClient(mService);
        final int interfaceIdx = 123;
        final DiscoveryListener discListener = mock(DiscoveryListener.class);
        client.discoverServices(SERVICE_TYPE, PROTOCOL, discListener);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> discIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).discover(discIdCaptor.capture(), eq(SERVICE_TYPE),
                eq(0) /* interfaceIdx */);
        // NsdManager uses a separate HandlerThread to dispatch callbacks (on ServiceHandler), so
        // this needs to use a timeout
        verify(discListener, timeout(TIMEOUT_MS)).onDiscoveryStarted(SERVICE_TYPE);

        final DiscoveryInfo discoveryInfo = new DiscoveryInfo(
                discIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_FOUND,
                SERVICE_NAME,
                SERVICE_TYPE,
                DOMAIN_NAME,
                interfaceIdx,
                INetd.LOCAL_NET_ID); // LOCAL_NET_ID (99) used on tethering downstreams
        eventListener.onServiceDiscoveryStatus(discoveryInfo);
        waitForIdle();

        final ArgumentCaptor<NsdServiceInfo> discoveredInfoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        verify(discListener, timeout(TIMEOUT_MS)).onServiceFound(discoveredInfoCaptor.capture());
        final NsdServiceInfo foundInfo = discoveredInfoCaptor.getValue();
        assertEquals(SERVICE_NAME, foundInfo.getServiceName());
        assertEquals(SERVICE_TYPE, foundInfo.getServiceType());
        assertNull(foundInfo.getHost());
        assertNull(foundInfo.getNetwork());
        assertEquals(interfaceIdx, foundInfo.getInterfaceIndex());

        // After discovering the service, verify resolving it
        final ResolveListener resolveListener = mock(ResolveListener.class);
        client.resolveService(foundInfo, resolveListener);
        waitForIdle();

        final ArgumentCaptor<Integer> resolvIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).resolve(resolvIdCaptor.capture(), eq(SERVICE_NAME), eq(SERVICE_TYPE),
                eq("local.") /* domain */, eq(interfaceIdx));

        final int servicePort = 10123;
        final ResolutionInfo resolutionInfo = new ResolutionInfo(
                resolvIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_RESOLVED,
                null /* serviceName */,
                null /* serviceType */,
                null /* domain */,
                SERVICE_FULL_NAME,
                DOMAIN_NAME,
                servicePort,
                new byte[0] /* txtRecord */,
                interfaceIdx);

        doReturn(true).when(mMockMDnsM).getServiceAddress(anyInt(), any(), anyInt());
        eventListener.onServiceResolutionStatus(resolutionInfo);
        waitForIdle();

        final ArgumentCaptor<Integer> getAddrIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).getServiceAddress(getAddrIdCaptor.capture(), eq(DOMAIN_NAME),
                eq(interfaceIdx));

        final String serviceAddress = "192.0.2.123";
        final GetAddressInfo addressInfo = new GetAddressInfo(
                getAddrIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_GET_ADDR_SUCCESS,
                SERVICE_FULL_NAME,
                serviceAddress,
                interfaceIdx,
                INetd.LOCAL_NET_ID);
        eventListener.onGettingServiceAddressStatus(addressInfo);
        waitForIdle();

        final ArgumentCaptor<NsdServiceInfo> resInfoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        verify(resolveListener, timeout(TIMEOUT_MS)).onServiceResolved(resInfoCaptor.capture());
        final NsdServiceInfo resolvedService = resInfoCaptor.getValue();
        assertEquals(SERVICE_NAME, resolvedService.getServiceName());
        assertEquals("." + SERVICE_TYPE, resolvedService.getServiceType());
        assertEquals(parseNumericAddress(serviceAddress), resolvedService.getHost());
        assertEquals(servicePort, resolvedService.getPort());
        assertNull(resolvedService.getNetwork());
        assertEquals(interfaceIdx, resolvedService.getInterfaceIndex());
    }

    @Test
    public void testDiscoverOnBlackholeNetwork() throws Exception {
        final NsdManager client = connectClient(mService);
        final DiscoveryListener discListener = mock(DiscoveryListener.class);
        client.discoverServices(SERVICE_TYPE, PROTOCOL, discListener);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> discIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).discover(discIdCaptor.capture(), eq(SERVICE_TYPE),
                eq(0) /* interfaceIdx */);
        // NsdManager uses a separate HandlerThread to dispatch callbacks (on ServiceHandler), so
        // this needs to use a timeout
        verify(discListener, timeout(TIMEOUT_MS)).onDiscoveryStarted(SERVICE_TYPE);

        final DiscoveryInfo discoveryInfo = new DiscoveryInfo(
                discIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_FOUND,
                SERVICE_NAME,
                SERVICE_TYPE,
                DOMAIN_NAME,
                123 /* interfaceIdx */,
                INetd.DUMMY_NET_ID); // netId of the blackhole network
        eventListener.onServiceDiscoveryStatus(discoveryInfo);
        waitForIdle();

        verify(discListener, never()).onServiceFound(any());
    }

    @Test
    public void testServiceRegistrationSuccessfulAndFailed() throws Exception {
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        request.setPort(PORT);
        final RegistrationListener regListener = mock(RegistrationListener.class);
        client.registerService(request, PROTOCOL, regListener);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> regIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).registerService(regIdCaptor.capture(),
                eq(SERVICE_NAME), eq(SERVICE_TYPE), eq(PORT), any(), eq(IFACE_IDX_ANY));

        // Register service successfully.
        final RegistrationInfo registrationInfo = new RegistrationInfo(
                regIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_REGISTERED,
                SERVICE_NAME,
                SERVICE_TYPE,
                PORT,
                new byte[0] /* txtRecord */,
                IFACE_IDX_ANY);
        eventListener.onServiceRegistrationStatus(registrationInfo);

        final ArgumentCaptor<NsdServiceInfo> registeredInfoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        verify(regListener, timeout(TIMEOUT_MS))
                .onServiceRegistered(registeredInfoCaptor.capture());
        final NsdServiceInfo registeredInfo = registeredInfoCaptor.getValue();
        assertEquals(SERVICE_NAME, registeredInfo.getServiceName());

        // Fail to register service.
        final RegistrationInfo registrationFailedInfo = new RegistrationInfo(
                regIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_REGISTRATION_FAILED,
                null /* serviceName */,
                null /* registrationType */,
                0 /* port */,
                new byte[0] /* txtRecord */,
                IFACE_IDX_ANY);
        eventListener.onServiceRegistrationStatus(registrationFailedInfo);
        verify(regListener, timeout(TIMEOUT_MS))
                .onRegistrationFailed(any(), eq(FAILURE_INTERNAL_ERROR));
    }

    @Test
    public void testServiceDiscoveryFailed() throws Exception {
        final NsdManager client = connectClient(mService);
        final DiscoveryListener discListener = mock(DiscoveryListener.class);
        client.discoverServices(SERVICE_TYPE, PROTOCOL, discListener);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> discIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).discover(discIdCaptor.capture(), eq(SERVICE_TYPE), eq(IFACE_IDX_ANY));
        verify(discListener, timeout(TIMEOUT_MS)).onDiscoveryStarted(SERVICE_TYPE);

        // Fail to discover service.
        final DiscoveryInfo discoveryFailedInfo = new DiscoveryInfo(
                discIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_DISCOVERY_FAILED,
                null /* serviceName */,
                null /* registrationType */,
                null /* domainName */,
                IFACE_IDX_ANY,
                0 /* netId */);
        eventListener.onServiceDiscoveryStatus(discoveryFailedInfo);
        verify(discListener, timeout(TIMEOUT_MS))
                .onStartDiscoveryFailed(SERVICE_TYPE, FAILURE_INTERNAL_ERROR);
    }

    @Test
    public void testServiceResolutionFailed() throws Exception {
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        final ResolveListener resolveListener = mock(ResolveListener.class);
        client.resolveService(request, resolveListener);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> resolvIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).resolve(resolvIdCaptor.capture(), eq(SERVICE_NAME), eq(SERVICE_TYPE),
                eq("local.") /* domain */, eq(IFACE_IDX_ANY));

        // Fail to resolve service.
        final ResolutionInfo resolutionFailedInfo = new ResolutionInfo(
                resolvIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_RESOLUTION_FAILED,
                null /* serviceName */,
                null /* serviceType */,
                null /* domain */,
                null /* serviceFullName */,
                null /* domainName */,
                0 /* port */,
                new byte[0] /* txtRecord */,
                IFACE_IDX_ANY);
        eventListener.onServiceResolutionStatus(resolutionFailedInfo);
        verify(resolveListener, timeout(TIMEOUT_MS))
                .onResolveFailed(any(), eq(FAILURE_INTERNAL_ERROR));
    }

    @Test
    public void testGettingAddressFailed() throws Exception {
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        final ResolveListener resolveListener = mock(ResolveListener.class);
        client.resolveService(request, resolveListener);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> resolvIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).resolve(resolvIdCaptor.capture(), eq(SERVICE_NAME), eq(SERVICE_TYPE),
                eq("local.") /* domain */, eq(IFACE_IDX_ANY));

        // Resolve service successfully.
        final ResolutionInfo resolutionInfo = new ResolutionInfo(
                resolvIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_RESOLVED,
                null /* serviceName */,
                null /* serviceType */,
                null /* domain */,
                SERVICE_FULL_NAME,
                DOMAIN_NAME,
                PORT,
                new byte[0] /* txtRecord */,
                IFACE_IDX_ANY);
        doReturn(true).when(mMockMDnsM).getServiceAddress(anyInt(), any(), anyInt());
        eventListener.onServiceResolutionStatus(resolutionInfo);
        waitForIdle();

        final ArgumentCaptor<Integer> getAddrIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).getServiceAddress(getAddrIdCaptor.capture(), eq(DOMAIN_NAME),
                eq(IFACE_IDX_ANY));

        // Fail to get service address.
        final GetAddressInfo gettingAddrFailedInfo = new GetAddressInfo(
                getAddrIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_GET_ADDR_FAILED,
                null /* hostname */,
                null /* address */,
                IFACE_IDX_ANY,
                0 /* netId */);
        eventListener.onGettingServiceAddressStatus(gettingAddrFailedInfo);
        verify(resolveListener, timeout(TIMEOUT_MS))
                .onResolveFailed(any(), eq(FAILURE_INTERNAL_ERROR));
    }

    @Test
    public void testNoCrashWhenProcessResolutionAfterBinderDied() throws Exception {
        final NsdManager client = connectClient(mService);
        final INsdManagerCallback cb = getCallback();
        final IBinder.DeathRecipient deathRecipient = verifyLinkToDeath(cb);
        deathRecipient.binderDied();

        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        final ResolveListener resolveListener = mock(ResolveListener.class);
        client.resolveService(request, resolveListener);
        waitForIdle();

        verify(mMockMDnsM, never()).registerEventListener(any());
        verify(mMockMDnsM, never()).startDaemon();
        verify(mMockMDnsM, never()).resolve(anyInt() /* id */, anyString() /* serviceName */,
                anyString() /* registrationType */, anyString() /* domain */,
                anyInt()/* interfaceIdx */);
    }

    @Test
    public void testStopServiceResolution() {
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        final ResolveListener resolveListener = mock(ResolveListener.class);
        client.resolveService(request, resolveListener);
        waitForIdle();

        final ArgumentCaptor<Integer> resolvIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).resolve(resolvIdCaptor.capture(), eq(SERVICE_NAME), eq(SERVICE_TYPE),
                eq("local.") /* domain */, eq(IFACE_IDX_ANY));

        final int resolveId = resolvIdCaptor.getValue();
        client.stopServiceResolution(resolveListener);
        waitForIdle();

        verify(mMockMDnsM).stopOperation(resolveId);
        verify(resolveListener, timeout(TIMEOUT_MS)).onResolutionStopped(argThat(ns ->
                request.getServiceName().equals(ns.getServiceName())
                        && request.getServiceType().equals(ns.getServiceType())));
    }

    @Test
    public void testStopResolutionFailed() {
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        final ResolveListener resolveListener = mock(ResolveListener.class);
        client.resolveService(request, resolveListener);
        waitForIdle();

        final ArgumentCaptor<Integer> resolvIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).resolve(resolvIdCaptor.capture(), eq(SERVICE_NAME), eq(SERVICE_TYPE),
                eq("local.") /* domain */, eq(IFACE_IDX_ANY));

        final int resolveId = resolvIdCaptor.getValue();
        doReturn(false).when(mMockMDnsM).stopOperation(anyInt());
        client.stopServiceResolution(resolveListener);
        waitForIdle();

        verify(mMockMDnsM).stopOperation(resolveId);
        verify(resolveListener, timeout(TIMEOUT_MS)).onStopResolutionFailed(argThat(ns ->
                        request.getServiceName().equals(ns.getServiceName())
                                && request.getServiceType().equals(ns.getServiceType())),
                eq(FAILURE_OPERATION_NOT_RUNNING));
    }

    @Test @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
    public void testStopResolutionDuringGettingAddress() throws RemoteException {
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        final ResolveListener resolveListener = mock(ResolveListener.class);
        client.resolveService(request, resolveListener);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> resolvIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).resolve(resolvIdCaptor.capture(), eq(SERVICE_NAME), eq(SERVICE_TYPE),
                eq("local.") /* domain */, eq(IFACE_IDX_ANY));

        // Resolve service successfully.
        final ResolutionInfo resolutionInfo = new ResolutionInfo(
                resolvIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_RESOLVED,
                null /* serviceName */,
                null /* serviceType */,
                null /* domain */,
                SERVICE_FULL_NAME,
                DOMAIN_NAME,
                PORT,
                new byte[0] /* txtRecord */,
                IFACE_IDX_ANY);
        doReturn(true).when(mMockMDnsM).getServiceAddress(anyInt(), any(), anyInt());
        eventListener.onServiceResolutionStatus(resolutionInfo);
        waitForIdle();

        final ArgumentCaptor<Integer> getAddrIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).getServiceAddress(getAddrIdCaptor.capture(), eq(DOMAIN_NAME),
                eq(IFACE_IDX_ANY));

        final int getAddrId = getAddrIdCaptor.getValue();
        client.stopServiceResolution(resolveListener);
        waitForIdle();

        verify(mMockMDnsM).stopOperation(getAddrId);
        verify(resolveListener, timeout(TIMEOUT_MS)).onResolutionStopped(argThat(ns ->
                request.getServiceName().equals(ns.getServiceName())
                        && request.getServiceType().equals(ns.getServiceType())));
    }

    private void verifyUpdatedServiceInfo(NsdServiceInfo info, String serviceName,
            String serviceType, String address, int port, int interfaceIndex, Network network) {
        assertEquals(serviceName, info.getServiceName());
        assertEquals(serviceType, info.getServiceType());
        assertTrue(info.getHostAddresses().contains(parseNumericAddress(address)));
        assertEquals(port, info.getPort());
        assertEquals(network, info.getNetwork());
        assertEquals(interfaceIndex, info.getInterfaceIndex());
    }

    @Test
    public void testRegisterAndUnregisterServiceInfoCallback() throws RemoteException {
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        final NsdManager.ServiceInfoCallback serviceInfoCallback = mock(
                NsdManager.ServiceInfoCallback.class);
        client.registerServiceInfoCallback(request, Runnable::run, serviceInfoCallback);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> resolvIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).resolve(resolvIdCaptor.capture(), eq(SERVICE_NAME), eq(SERVICE_TYPE),
                eq("local.") /* domain */, eq(IFACE_IDX_ANY));

        // Resolve service successfully.
        final ResolutionInfo resolutionInfo = new ResolutionInfo(
                resolvIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_RESOLVED,
                null /* serviceName */,
                null /* serviceType */,
                null /* domain */,
                SERVICE_FULL_NAME,
                DOMAIN_NAME,
                PORT,
                new byte[0] /* txtRecord */,
                IFACE_IDX_ANY);
        doReturn(true).when(mMockMDnsM).getServiceAddress(anyInt(), any(), anyInt());
        eventListener.onServiceResolutionStatus(resolutionInfo);
        waitForIdle();

        final ArgumentCaptor<Integer> getAddrIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).getServiceAddress(getAddrIdCaptor.capture(), eq(DOMAIN_NAME),
                eq(IFACE_IDX_ANY));

        // First address info
        final String v4Address = "192.0.2.1";
        final String v6Address = "2001:db8::";
        final GetAddressInfo addressInfo1 = new GetAddressInfo(
                getAddrIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_GET_ADDR_SUCCESS,
                SERVICE_FULL_NAME,
                v4Address,
                IFACE_IDX_ANY,
                999 /* netId */);
        eventListener.onGettingServiceAddressStatus(addressInfo1);
        waitForIdle();

        final ArgumentCaptor<NsdServiceInfo> updateInfoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        verify(serviceInfoCallback, timeout(TIMEOUT_MS).times(1))
                .onServiceUpdated(updateInfoCaptor.capture());
        verifyUpdatedServiceInfo(updateInfoCaptor.getAllValues().get(0) /* info */, SERVICE_NAME,
                "." + SERVICE_TYPE, v4Address, PORT, IFACE_IDX_ANY, new Network(999));

        // Second address info
        final GetAddressInfo addressInfo2 = new GetAddressInfo(
                getAddrIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_GET_ADDR_SUCCESS,
                SERVICE_FULL_NAME,
                v6Address,
                IFACE_IDX_ANY,
                999 /* netId */);
        eventListener.onGettingServiceAddressStatus(addressInfo2);
        waitForIdle();

        verify(serviceInfoCallback, timeout(TIMEOUT_MS).times(2))
                .onServiceUpdated(updateInfoCaptor.capture());
        verifyUpdatedServiceInfo(updateInfoCaptor.getAllValues().get(1) /* info */, SERVICE_NAME,
                "." + SERVICE_TYPE, v6Address, PORT, IFACE_IDX_ANY, new Network(999));

        client.unregisterServiceInfoCallback(serviceInfoCallback);
        waitForIdle();

        verify(serviceInfoCallback, timeout(TIMEOUT_MS)).onServiceInfoCallbackUnregistered();
    }

    @Test
    public void testRegisterServiceCallbackFailed() throws Exception {
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        final NsdManager.ServiceInfoCallback subscribeListener = mock(
                NsdManager.ServiceInfoCallback.class);
        client.registerServiceInfoCallback(request, Runnable::run, subscribeListener);
        waitForIdle();

        final IMDnsEventListener eventListener = getEventListener();
        final ArgumentCaptor<Integer> resolvIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).resolve(resolvIdCaptor.capture(), eq(SERVICE_NAME), eq(SERVICE_TYPE),
                eq("local.") /* domain */, eq(IFACE_IDX_ANY));

        // Fail to resolve service.
        final ResolutionInfo resolutionFailedInfo = new ResolutionInfo(
                resolvIdCaptor.getValue(),
                IMDnsEventListener.SERVICE_RESOLUTION_FAILED,
                null /* serviceName */,
                null /* serviceType */,
                null /* domain */,
                null /* serviceFullName */,
                null /* domainName */,
                0 /* port */,
                new byte[0] /* txtRecord */,
                IFACE_IDX_ANY);
        eventListener.onServiceResolutionStatus(resolutionFailedInfo);
        verify(subscribeListener, timeout(TIMEOUT_MS))
                .onServiceInfoCallbackRegistrationFailed(eq(FAILURE_BAD_PARAMETERS));
    }

    @Test
    public void testUnregisterNotRegisteredCallback() {
        final NsdManager client = connectClient(mService);
        final NsdManager.ServiceInfoCallback serviceInfoCallback = mock(
                NsdManager.ServiceInfoCallback.class);

        assertThrows(IllegalArgumentException.class, () ->
                client.unregisterServiceInfoCallback(serviceInfoCallback));
    }

    private void setMdnsDiscoveryManagerEnabled() {
        doReturn(true).when(mDeps).isMdnsDiscoveryManagerEnabled(any(Context.class));
    }

    private void setMdnsAdvertiserEnabled() {
        doReturn(true).when(mDeps).isMdnsAdvertiserEnabled(any(Context.class));
    }

    @Test
    public void testMdnsDiscoveryManagerFeature() {
        // Create NsdService w/o feature enabled.
        final NsdManager client = connectClient(mService);
        final DiscoveryListener discListenerWithoutFeature = mock(DiscoveryListener.class);
        client.discoverServices(SERVICE_TYPE, PROTOCOL, discListenerWithoutFeature);
        waitForIdle();

        final ArgumentCaptor<Integer> legacyIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).discover(legacyIdCaptor.capture(), any(), anyInt());
        verifyNoMoreInteractions(mDiscoveryManager);

        setMdnsDiscoveryManagerEnabled();
        final DiscoveryListener discListenerWithFeature = mock(DiscoveryListener.class);
        client.discoverServices(SERVICE_TYPE, PROTOCOL, discListenerWithFeature);
        waitForIdle();

        final String serviceTypeWithLocalDomain = SERVICE_TYPE + ".local";
        final ArgumentCaptor<MdnsServiceBrowserListener> listenerCaptor =
                ArgumentCaptor.forClass(MdnsServiceBrowserListener.class);
        verify(mDiscoveryManager).registerListener(eq(serviceTypeWithLocalDomain),
                listenerCaptor.capture(), any());

        client.stopServiceDiscovery(discListenerWithoutFeature);
        waitForIdle();
        verify(mMockMDnsM).stopOperation(legacyIdCaptor.getValue());

        client.stopServiceDiscovery(discListenerWithFeature);
        waitForIdle();
        verify(mDiscoveryManager).unregisterListener(serviceTypeWithLocalDomain,
                listenerCaptor.getValue());
    }

    @Test
    public void testDiscoveryWithMdnsDiscoveryManager() {
        setMdnsDiscoveryManagerEnabled();

        final NsdManager client = connectClient(mService);
        final DiscoveryListener discListener = mock(DiscoveryListener.class);
        final Network network = new Network(999);
        final String serviceTypeWithLocalDomain = SERVICE_TYPE + ".local";
        // Verify the discovery start / stop.
        final ArgumentCaptor<MdnsServiceBrowserListener> listenerCaptor =
                ArgumentCaptor.forClass(MdnsServiceBrowserListener.class);
        client.discoverServices(SERVICE_TYPE, PROTOCOL, network, r -> r.run(), discListener);
        waitForIdle();
        verify(mSocketProvider).startMonitoringSockets();
        verify(mDiscoveryManager).registerListener(eq(serviceTypeWithLocalDomain),
                listenerCaptor.capture(), argThat(options -> network.equals(options.getNetwork())));
        verify(discListener, timeout(TIMEOUT_MS)).onDiscoveryStarted(SERVICE_TYPE);

        final MdnsServiceBrowserListener listener = listenerCaptor.getValue();
        final MdnsServiceInfo foundInfo = new MdnsServiceInfo(
                SERVICE_NAME, /* serviceInstanceName */
                serviceTypeWithLocalDomain.split("\\."), /* serviceType */
                List.of(), /* subtypes */
                new String[] {"android", "local"}, /* hostName */
                12345, /* port */
                IPV4_ADDRESS,
                IPV6_ADDRESS,
                List.of(), /* textStrings */
                List.of(), /* textEntries */
                1234, /* interfaceIndex */
                network);

        // Verify onServiceNameDiscovered callback
        listener.onServiceNameDiscovered(foundInfo);
        verify(discListener, timeout(TIMEOUT_MS)).onServiceFound(argThat(info ->
                info.getServiceName().equals(SERVICE_NAME)
                        && info.getServiceType().equals(SERVICE_TYPE)
                        && info.getNetwork().equals(network)));

        final MdnsServiceInfo removedInfo = new MdnsServiceInfo(
                SERVICE_NAME, /* serviceInstanceName */
                serviceTypeWithLocalDomain.split("\\."), /* serviceType */
                null, /* subtypes */
                null, /* hostName */
                0, /* port */
                null, /* ipv4Address */
                null, /* ipv6Address */
                null, /* textStrings */
                null, /* textEntries */
                1234, /* interfaceIndex */
                network);
        // Verify onServiceNameRemoved callback
        listener.onServiceNameRemoved(removedInfo);
        verify(discListener, timeout(TIMEOUT_MS)).onServiceLost(argThat(info ->
                info.getServiceName().equals(SERVICE_NAME)
                        && info.getServiceType().equals(SERVICE_TYPE)
                        && info.getNetwork().equals(network)));

        client.stopServiceDiscovery(discListener);
        waitForIdle();
        verify(mDiscoveryManager).unregisterListener(eq(serviceTypeWithLocalDomain), any());
        verify(discListener, timeout(TIMEOUT_MS)).onDiscoveryStopped(SERVICE_TYPE);
        verify(mSocketProvider, timeout(CLEANUP_DELAY_MS + TIMEOUT_MS)).requestStopWhenInactive();
    }

    @Test
    public void testDiscoveryWithMdnsDiscoveryManager_FailedWithInvalidServiceType() {
        setMdnsDiscoveryManagerEnabled();

        final NsdManager client = connectClient(mService);
        final DiscoveryListener discListener = mock(DiscoveryListener.class);
        final Network network = new Network(999);
        final String invalidServiceType = "a_service";
        client.discoverServices(
                invalidServiceType, PROTOCOL, network, r -> r.run(), discListener);
        waitForIdle();
        verify(discListener, timeout(TIMEOUT_MS))
                .onStartDiscoveryFailed(invalidServiceType, FAILURE_INTERNAL_ERROR);

        final String serviceTypeWithLocalDomain = SERVICE_TYPE + ".local";
        client.discoverServices(
                serviceTypeWithLocalDomain, PROTOCOL, network, r -> r.run(), discListener);
        waitForIdle();
        verify(discListener, timeout(TIMEOUT_MS))
                .onStartDiscoveryFailed(serviceTypeWithLocalDomain, FAILURE_INTERNAL_ERROR);

        final String serviceTypeWithoutTcpOrUdpEnding = "_test._com";
        client.discoverServices(
                serviceTypeWithoutTcpOrUdpEnding, PROTOCOL, network, r -> r.run(), discListener);
        waitForIdle();
        verify(discListener, timeout(TIMEOUT_MS))
                .onStartDiscoveryFailed(serviceTypeWithoutTcpOrUdpEnding, FAILURE_INTERNAL_ERROR);
    }

    @Test
    public void testResolutionWithMdnsDiscoveryManager() throws UnknownHostException {
        setMdnsDiscoveryManagerEnabled();

        final NsdManager client = connectClient(mService);
        final ResolveListener resolveListener = mock(ResolveListener.class);
        final Network network = new Network(999);
        final String serviceType = "_nsd._service._tcp";
        final String constructedServiceType = "_nsd._sub._service._tcp.local";
        final ArgumentCaptor<MdnsServiceBrowserListener> listenerCaptor =
                ArgumentCaptor.forClass(MdnsServiceBrowserListener.class);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, serviceType);
        request.setNetwork(network);
        client.resolveService(request, resolveListener);
        waitForIdle();
        verify(mSocketProvider).startMonitoringSockets();
        verify(mDiscoveryManager).registerListener(eq(constructedServiceType),
                listenerCaptor.capture(), argThat(options -> network.equals(options.getNetwork())));

        final MdnsServiceBrowserListener listener = listenerCaptor.getValue();
        final MdnsServiceInfo mdnsServiceInfo = new MdnsServiceInfo(
                SERVICE_NAME,
                constructedServiceType.split("\\."),
                List.of(), /* subtypes */
                new String[]{"android", "local"}, /* hostName */
                PORT,
                IPV4_ADDRESS,
                IPV6_ADDRESS,
                List.of() /* textStrings */,
                List.of(MdnsServiceInfo.TextEntry.fromBytes(new byte[]{
                        'k', 'e', 'y', '=', (byte) 0xFF, (byte) 0xFE})) /* textEntries */,
                1234,
                network);

        // Verify onServiceFound callback
        listener.onServiceFound(mdnsServiceInfo);
        final ArgumentCaptor<NsdServiceInfo> infoCaptor =
                ArgumentCaptor.forClass(NsdServiceInfo.class);
        verify(resolveListener, timeout(TIMEOUT_MS)).onServiceResolved(infoCaptor.capture());
        final NsdServiceInfo info = infoCaptor.getValue();
        assertEquals(SERVICE_NAME, info.getServiceName());
        assertEquals("." + serviceType, info.getServiceType());
        assertEquals(PORT, info.getPort());
        assertTrue(info.getAttributes().containsKey("key"));
        assertEquals(1, info.getAttributes().size());
        assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFE}, info.getAttributes().get("key"));
        assertEquals(parseNumericAddress(IPV4_ADDRESS), info.getHost());
        assertEquals(network, info.getNetwork());

        // Verify the listener has been unregistered.
        verify(mDiscoveryManager, timeout(TIMEOUT_MS))
                .unregisterListener(eq(constructedServiceType), any());
        verify(mSocketProvider, timeout(CLEANUP_DELAY_MS + TIMEOUT_MS)).requestStopWhenInactive();
    }

    @Test
    public void testMdnsAdvertiserFeatureFlagging() {
        // Create NsdService w/o feature enabled.
        final NsdManager client = connectClient(mService);
        final NsdServiceInfo regInfo = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        regInfo.setHost(parseNumericAddress("192.0.2.123"));
        regInfo.setPort(12345);
        final RegistrationListener regListenerWithoutFeature = mock(RegistrationListener.class);
        client.registerService(regInfo, PROTOCOL, regListenerWithoutFeature);
        waitForIdle();

        final ArgumentCaptor<Integer> legacyIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mMockMDnsM).registerService(legacyIdCaptor.capture(), any(), any(), anyInt(),
                any(), anyInt());
        verifyNoMoreInteractions(mAdvertiser);

        setMdnsAdvertiserEnabled();
        final RegistrationListener regListenerWithFeature = mock(RegistrationListener.class);
        client.registerService(regInfo, PROTOCOL, regListenerWithFeature);
        waitForIdle();

        final ArgumentCaptor<Integer> serviceIdCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mAdvertiser).addService(serviceIdCaptor.capture(),
                argThat(info -> matches(info, regInfo)));

        client.unregisterService(regListenerWithoutFeature);
        waitForIdle();
        verify(mMockMDnsM).stopOperation(legacyIdCaptor.getValue());
        verify(mAdvertiser, never()).removeService(anyInt());

        client.unregisterService(regListenerWithFeature);
        waitForIdle();
        verify(mAdvertiser).removeService(serviceIdCaptor.getValue());
    }

    @Test
    public void testAdvertiseWithMdnsAdvertiser() {
        setMdnsAdvertiserEnabled();

        final NsdManager client = connectClient(mService);
        final RegistrationListener regListener = mock(RegistrationListener.class);
        // final String serviceTypeWithLocalDomain = SERVICE_TYPE + ".local";
        final ArgumentCaptor<MdnsAdvertiser.AdvertiserCallback> cbCaptor =
                ArgumentCaptor.forClass(MdnsAdvertiser.AdvertiserCallback.class);
        verify(mDeps).makeMdnsAdvertiser(any(), any(), cbCaptor.capture());

        final NsdServiceInfo regInfo = new NsdServiceInfo(SERVICE_NAME, SERVICE_TYPE);
        regInfo.setHost(parseNumericAddress("192.0.2.123"));
        regInfo.setPort(12345);
        regInfo.setAttribute("testattr", "testvalue");
        regInfo.setNetwork(new Network(999));

        client.registerService(regInfo, NsdManager.PROTOCOL_DNS_SD, Runnable::run, regListener);
        waitForIdle();
        verify(mSocketProvider).startMonitoringSockets();
        final ArgumentCaptor<Integer> idCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(mAdvertiser).addService(idCaptor.capture(), argThat(info ->
                matches(info, regInfo)));

        // Verify onServiceRegistered callback
        final MdnsAdvertiser.AdvertiserCallback cb = cbCaptor.getValue();
        cb.onRegisterServiceSucceeded(idCaptor.getValue(), regInfo);

        verify(regListener, timeout(TIMEOUT_MS)).onServiceRegistered(argThat(info -> matches(info,
                new NsdServiceInfo(regInfo.getServiceName(), null))));

        client.unregisterService(regListener);
        waitForIdle();
        verify(mAdvertiser).removeService(idCaptor.getValue());
        verify(regListener, timeout(TIMEOUT_MS)).onServiceUnregistered(
                argThat(info -> matches(info, regInfo)));
        verify(mSocketProvider, timeout(TIMEOUT_MS)).requestStopWhenInactive();
    }

    @Test
    public void testAdvertiseWithMdnsAdvertiser_FailedWithInvalidServiceType() {
        setMdnsAdvertiserEnabled();

        final NsdManager client = connectClient(mService);
        final RegistrationListener regListener = mock(RegistrationListener.class);
        // final String serviceTypeWithLocalDomain = SERVICE_TYPE + ".local";
        final ArgumentCaptor<MdnsAdvertiser.AdvertiserCallback> cbCaptor =
                ArgumentCaptor.forClass(MdnsAdvertiser.AdvertiserCallback.class);
        verify(mDeps).makeMdnsAdvertiser(any(), any(), cbCaptor.capture());

        final NsdServiceInfo regInfo = new NsdServiceInfo(SERVICE_NAME, "invalid_type");
        regInfo.setHost(parseNumericAddress("192.0.2.123"));
        regInfo.setPort(12345);
        regInfo.setAttribute("testattr", "testvalue");
        regInfo.setNetwork(new Network(999));

        client.registerService(regInfo, NsdManager.PROTOCOL_DNS_SD, Runnable::run, regListener);
        waitForIdle();
        verify(mAdvertiser, never()).addService(anyInt(), any());

        verify(regListener, timeout(TIMEOUT_MS)).onRegistrationFailed(
                argThat(info -> matches(info, regInfo)), eq(FAILURE_INTERNAL_ERROR));
    }

    @Test
    public void testAdvertiseWithMdnsAdvertiser_LongServiceName() {
        setMdnsAdvertiserEnabled();

        final NsdManager client = connectClient(mService);
        final RegistrationListener regListener = mock(RegistrationListener.class);
        // final String serviceTypeWithLocalDomain = SERVICE_TYPE + ".local";
        final ArgumentCaptor<MdnsAdvertiser.AdvertiserCallback> cbCaptor =
                ArgumentCaptor.forClass(MdnsAdvertiser.AdvertiserCallback.class);
        verify(mDeps).makeMdnsAdvertiser(any(), any(), cbCaptor.capture());

        final NsdServiceInfo regInfo = new NsdServiceInfo("a".repeat(70), SERVICE_TYPE);
        regInfo.setHost(parseNumericAddress("192.0.2.123"));
        regInfo.setPort(12345);
        regInfo.setAttribute("testattr", "testvalue");
        regInfo.setNetwork(new Network(999));

        client.registerService(regInfo, NsdManager.PROTOCOL_DNS_SD, Runnable::run, regListener);
        waitForIdle();
        final ArgumentCaptor<Integer> idCaptor = ArgumentCaptor.forClass(Integer.class);
        // Service name is truncated to 63 characters
        verify(mAdvertiser).addService(idCaptor.capture(),
                argThat(info -> info.getServiceName().equals("a".repeat(63))));

        // Verify onServiceRegistered callback
        final MdnsAdvertiser.AdvertiserCallback cb = cbCaptor.getValue();
        cb.onRegisterServiceSucceeded(idCaptor.getValue(), regInfo);

        verify(regListener, timeout(TIMEOUT_MS)).onServiceRegistered(
                argThat(info -> matches(info, new NsdServiceInfo(regInfo.getServiceName(), null))));
    }

    @Test
    public void testStopServiceResolutionWithMdnsDiscoveryManager() {
        setMdnsDiscoveryManagerEnabled();

        final NsdManager client = connectClient(mService);
        final ResolveListener resolveListener = mock(ResolveListener.class);
        final Network network = new Network(999);
        final String serviceType = "_nsd._service._tcp";
        final String constructedServiceType = "_nsd._sub._service._tcp.local";
        final ArgumentCaptor<MdnsServiceBrowserListener> listenerCaptor =
                ArgumentCaptor.forClass(MdnsServiceBrowserListener.class);
        final NsdServiceInfo request = new NsdServiceInfo(SERVICE_NAME, serviceType);
        request.setNetwork(network);
        client.resolveService(request, resolveListener);
        waitForIdle();
        verify(mSocketProvider).startMonitoringSockets();
        verify(mDiscoveryManager).registerListener(eq(constructedServiceType),
                listenerCaptor.capture(), argThat(options -> network.equals(options.getNetwork())));

        client.stopServiceResolution(resolveListener);
        waitForIdle();

        // Verify the listener has been unregistered.
        verify(mDiscoveryManager, timeout(TIMEOUT_MS))
                .unregisterListener(eq(constructedServiceType), eq(listenerCaptor.getValue()));
        verify(resolveListener, timeout(TIMEOUT_MS)).onResolutionStopped(argThat(ns ->
                request.getServiceName().equals(ns.getServiceName())
                        && request.getServiceType().equals(ns.getServiceType())));
        verify(mSocketProvider, timeout(CLEANUP_DELAY_MS + TIMEOUT_MS)).requestStopWhenInactive();
    }

    @Test
    public void testConstructServiceType() {
        final String serviceType1 = "test._tcp";
        final String serviceType2 = "_test._quic";
        final String serviceType3 = "_123._udp.";
        final String serviceType4 = "_TEST._999._tcp.";

        assertEquals(null, constructServiceType(serviceType1));
        assertEquals(null, constructServiceType(serviceType2));
        assertEquals("_123._udp", constructServiceType(serviceType3));
        assertEquals("_TEST._sub._999._tcp", constructServiceType(serviceType4));
    }

    private void waitForIdle() {
        HandlerUtils.waitForIdle(mHandler, TIMEOUT_MS);
    }

    NsdService makeService() {
        final NsdService service = new NsdService(mContext, mHandler, CLEANUP_DELAY_MS, mDeps) {
            @Override
            public INsdServiceConnector connect(INsdManagerCallback baseCb) {
                // Wrap the callback in a transparent mock, to mock asBinder returning a
                // LinkToDeathRecorder. This will allow recording the binder death recipient
                // registered on the callback. Use a transparent mock and not a spy as the actual
                // implementation class is not public and cannot be spied on by Mockito.
                final INsdManagerCallback cb = mock(INsdManagerCallback.class,
                        AdditionalAnswers.delegatesTo(baseCb));
                doReturn(new LinkToDeathRecorder()).when(cb).asBinder();
                mCreatedCallbacks.add(cb);
                return super.connect(cb);
            }
        };
        return service;
    }

    private INsdManagerCallback getCallback() {
        return mCreatedCallbacks.remove();
    }

    NsdManager connectClient(NsdService service) {
        final NsdManager nsdManager = new NsdManager(mContext, service);
        // Wait for client registration done.
        waitForIdle();
        return nsdManager;
    }

    void verifyDelayMaybeStopDaemon(long cleanupDelayMs) throws Exception {
        waitForIdle();
        // Stop daemon shouldn't be called immediately.
        verify(mMockMDnsM, never()).unregisterEventListener(any());
        verify(mMockMDnsM, never()).stopDaemon();

        // Clean up the daemon after CLEANUP_DELAY_MS.
        verify(mMockMDnsM, timeout(cleanupDelayMs + TIMEOUT_MS)).unregisterEventListener(any());
        verify(mMockMDnsM, timeout(cleanupDelayMs + TIMEOUT_MS)).stopDaemon();
    }

    /**
     * Return true if two service info are the same.
     *
     * Useful for argument matchers as {@link NsdServiceInfo} does not implement equals.
     */
    private boolean matches(NsdServiceInfo a, NsdServiceInfo b) {
        return Objects.equals(a.getServiceName(), b.getServiceName())
                && Objects.equals(a.getServiceType(), b.getServiceType())
                && Objects.equals(a.getHost(), b.getHost())
                && Objects.equals(a.getNetwork(), b.getNetwork())
                && Objects.equals(a.getAttributes(), b.getAttributes());
    }

    public static class TestHandler extends Handler {
        public Message lastMessage;

        TestHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            lastMessage = obtainMessage();
            lastMessage.copyFrom(msg);
        }
    }
}
