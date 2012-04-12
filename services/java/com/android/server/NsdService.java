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

package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.DnsSdServiceInfo;
import android.net.nsd.DnsSdTxtRecord;
import android.net.nsd.INsdManager;
import android.net.nsd.NsdManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Messenger;
import android.os.IBinder;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import com.android.internal.app.IBatteryStats;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import com.android.server.am.BatteryStatsService;
import com.android.server.NativeDaemonConnector.Command;
import com.android.internal.R;

/**
 * Network Service Discovery Service handles remote service discovery operation requests by
 * implementing the INsdManager interface.
 *
 * @hide
 */
public class NsdService extends INsdManager.Stub {
    private static final String TAG = "NsdService";
    private static final String MDNS_TAG = "mDnsConnector";

    private static final boolean DBG = true;

    private Context mContext;

    /**
     * Clients receiving asynchronous messages
     */
    private HashMap<Messenger, ClientInfo> mClients = new HashMap<Messenger, ClientInfo>();

    private AsyncChannel mReplyChannel = new AsyncChannel();

    private int INVALID_ID = 0;
    private int mUniqueId = 1;

    /**
     * Handles client(app) connections
     */
    private class AsyncServiceHandler extends Handler {

        AsyncServiceHandler(android.os.Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            ClientInfo clientInfo;
            DnsSdServiceInfo servInfo;
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                        AsyncChannel c = (AsyncChannel) msg.obj;
                        if (DBG) Slog.d(TAG, "New client listening to asynchronous messages");
                        c.sendMessage(AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED);
                        ClientInfo cInfo = new ClientInfo(c, msg.replyTo);
                        if (mClients.size() == 0) {
                            startMDnsDaemon();
                        }
                        mClients.put(msg.replyTo, cInfo);
                    } else {
                        Slog.e(TAG, "Client connection failure, error=" + msg.arg1);
                    }
                    break;
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                    if (msg.arg1 == AsyncChannel.STATUS_SEND_UNSUCCESSFUL) {
                        Slog.e(TAG, "Send failed, client connection lost");
                    } else {
                        if (DBG) Slog.d(TAG, "Client connection lost with reason: " + msg.arg1);
                    }
                    mClients.remove(msg.replyTo);
                    if (mClients.size() == 0) {
                        stopMDnsDaemon();
                    }
                    break;
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                    AsyncChannel ac = new AsyncChannel();
                    ac.connect(mContext, this, msg.replyTo);
                    break;
                case NsdManager.DISCOVER_SERVICES:
                    if (DBG) Slog.d(TAG, "Discover services");
                    servInfo = (DnsSdServiceInfo) msg.obj;
                    clientInfo = mClients.get(msg.replyTo);
                    if (clientInfo.mDiscoveryId != INVALID_ID) {
                        //discovery already in progress
                        if (DBG) Slog.d(TAG, "discovery in progress");
                        mReplyChannel.replyToMessage(msg, NsdManager.DISCOVER_SERVICES_FAILED,
                                NsdManager.ALREADY_ACTIVE);
                        break;
                    }
                    clientInfo.mDiscoveryId = getUniqueId();
                    if (discoverServices(clientInfo.mDiscoveryId, servInfo.getServiceType())) {
                        mReplyChannel.replyToMessage(msg, NsdManager.DISCOVER_SERVICES_STARTED);
                    } else {
                        mReplyChannel.replyToMessage(msg, NsdManager.DISCOVER_SERVICES_FAILED,
                                NsdManager.ERROR);
                        clientInfo.mDiscoveryId = INVALID_ID;
                    }
                    break;
                case NsdManager.STOP_DISCOVERY:
                    if (DBG) Slog.d(TAG, "Stop service discovery");
                    clientInfo = mClients.get(msg.replyTo);
                    if (clientInfo.mDiscoveryId == INVALID_ID) {
                        //already stopped
                        if (DBG) Slog.d(TAG, "discovery already stopped");
                        mReplyChannel.replyToMessage(msg, NsdManager.STOP_DISCOVERY_FAILED,
                                NsdManager.ALREADY_ACTIVE);
                        break;
                    }
                    if (stopServiceDiscovery(clientInfo.mDiscoveryId)) {
                        clientInfo.mDiscoveryId = INVALID_ID;
                        mReplyChannel.replyToMessage(msg, NsdManager.STOP_DISCOVERY_SUCCEEDED);
                    } else {
                        mReplyChannel.replyToMessage(msg, NsdManager.STOP_DISCOVERY_FAILED,
                                NsdManager.ERROR);
                    }
                    break;
                case NsdManager.REGISTER_SERVICE:
                    if (DBG) Slog.d(TAG, "Register service");
                    clientInfo = mClients.get(msg.replyTo);
                    if (clientInfo.mRegisteredIds.size() >= ClientInfo.MAX_REG) {
                        if (DBG) Slog.d(TAG, "register service exceeds limit");
                        mReplyChannel.replyToMessage(msg, NsdManager.REGISTER_SERVICE_FAILED,
                                NsdManager.MAX_REGS_REACHED);
                    }

                    int id = getUniqueId();
                    if (registerService(id, (DnsSdServiceInfo) msg.obj)) {
                        clientInfo.mRegisteredIds.add(id);
                    } else {
                        mReplyChannel.replyToMessage(msg, NsdManager.REGISTER_SERVICE_FAILED,
                                NsdManager.ERROR);
                    }
                    break;
                case NsdManager.UPDATE_SERVICE:
                    if (DBG) Slog.d(TAG, "Update service");
                    //TODO: implement
                    mReplyChannel.replyToMessage(msg, NsdManager.UPDATE_SERVICE_FAILED);
                    break;
                case NsdManager.RESOLVE_SERVICE:
                    if (DBG) Slog.d(TAG, "Resolve service");
                    servInfo = (DnsSdServiceInfo) msg.obj;
                    clientInfo = mClients.get(msg.replyTo);
                    if (clientInfo.mResolveId != INVALID_ID) {
                        //first cancel existing resolve
                        stopResolveService(clientInfo.mResolveId);
                    }

                    clientInfo.mResolveId = getUniqueId();
                    if (!resolveService(clientInfo.mResolveId, servInfo)) {
                        mReplyChannel.replyToMessage(msg, NsdManager.RESOLVE_SERVICE_FAILED,
                                NsdManager.ERROR);
                        clientInfo.mResolveId = INVALID_ID;
                    }
                    break;
                case NsdManager.STOP_RESOLVE:
                    if (DBG) Slog.d(TAG, "Stop resolve");
                    clientInfo = mClients.get(msg.replyTo);
                    if (clientInfo.mResolveId == INVALID_ID) {
                        //already stopped
                        if (DBG) Slog.d(TAG, "resolve already stopped");
                        mReplyChannel.replyToMessage(msg, NsdManager.STOP_RESOLVE_FAILED,
                                NsdManager.ALREADY_ACTIVE);
                        break;
                    }
                    if (stopResolveService(clientInfo.mResolveId)) {
                        clientInfo.mResolveId = INVALID_ID;
                        mReplyChannel.replyToMessage(msg, NsdManager.STOP_RESOLVE_SUCCEEDED);
                    } else {
                        mReplyChannel.replyToMessage(msg, NsdManager.STOP_RESOLVE_FAILED,
                                NsdManager.ERROR);
                    }
                    break;
                default:
                    Slog.d(TAG, "NsdServicehandler.handleMessage ignoring msg=" + msg);
                    break;
            }
        }
    }
    private AsyncServiceHandler mAsyncServiceHandler;

    private NativeDaemonConnector mNativeConnector;
    private final CountDownLatch mNativeDaemonConnected = new CountDownLatch(1);

    private NsdService(Context context) {
        mContext = context;

        HandlerThread nsdThread = new HandlerThread("NsdService");
        nsdThread.start();
        mAsyncServiceHandler = new AsyncServiceHandler(nsdThread.getLooper());

        mNativeConnector = new NativeDaemonConnector(new NativeCallbackReceiver(), "mdns", 10,
                MDNS_TAG, 25);
        Thread th = new Thread(mNativeConnector, MDNS_TAG);
        th.start();
    }

    public static NsdService create(Context context) throws InterruptedException {
        NsdService service = new NsdService(context);
        /* service.mNativeDaemonConnected.await(); */
        return service;
    }

    public Messenger getMessenger() {
        return new Messenger(mAsyncServiceHandler);
    }

    private int getUniqueId() {
        if (++mUniqueId == INVALID_ID) return ++mUniqueId;
        return mUniqueId;
    }

    /* These should be in sync with system/netd/mDnsResponseCode.h */
    class NativeResponseCode {
        public static final int SERVICE_DISCOVERY_FAILED    =   602;
        public static final int SERVICE_FOUND               =   603;
        public static final int SERVICE_LOST                =   604;

        public static final int SERVICE_REGISTRATION_FAILED =   605;
        public static final int SERVICE_REGISTERED          =   606;

        public static final int SERVICE_RESOLUTION_FAILED   =   607;
        public static final int SERVICE_RESOLVED            =   608;

        public static final int SERVICE_UPDATED             =   609;
        public static final int SERVICE_UPDATE_FAILED       =   610;

        public static final int SERVICE_GET_ADDR_FAILED     =   611;
        public static final int SERVICE_GET_ADDR_SUCCESS    =   612;
    }

    class NativeCallbackReceiver implements INativeDaemonConnectorCallbacks {
        public void onDaemonConnected() {
            mNativeDaemonConnected.countDown();
        }

        public boolean onEvent(int code, String raw, String[] cooked) {
            ClientInfo clientInfo;
            DnsSdServiceInfo servInfo;
            int id = Integer.parseInt(cooked[1]);
            switch (code) {
                case NativeResponseCode.SERVICE_FOUND:
                    /* NNN uniqueId serviceName regType domain */
                    if (DBG) Slog.d(TAG, "SERVICE_FOUND Raw: " + raw);
                    clientInfo = getClientByDiscovery(id);
                    if (clientInfo == null) break;

                    servInfo = new DnsSdServiceInfo(cooked[2], cooked[3], null);
                    clientInfo.mChannel.sendMessage(NsdManager.SERVICE_FOUND, servInfo);
                    break;
                case NativeResponseCode.SERVICE_LOST:
                    /* NNN uniqueId serviceName regType domain */
                    if (DBG) Slog.d(TAG, "SERVICE_LOST Raw: " + raw);
                    clientInfo = getClientByDiscovery(id);
                    if (clientInfo == null) break;

                    servInfo = new DnsSdServiceInfo(cooked[2], cooked[3], null);
                    clientInfo.mChannel.sendMessage(NsdManager.SERVICE_LOST, servInfo);
                    break;
                case NativeResponseCode.SERVICE_DISCOVERY_FAILED:
                    /* NNN uniqueId errorCode */
                    if (DBG) Slog.d(TAG, "SERVICE_DISC_FAILED Raw: " + raw);
                    clientInfo = getClientByDiscovery(id);
                    if (clientInfo == null) break;

                    clientInfo.mChannel.sendMessage(NsdManager.DISCOVER_SERVICES_FAILED,
                            NsdManager.ERROR);
                    break;
                case NativeResponseCode.SERVICE_REGISTERED:
                    /* NNN regId serviceName regType */
                    if (DBG) Slog.d(TAG, "SERVICE_REGISTERED Raw: " + raw);
                    clientInfo = getClientByRegistration(id);
                    if (clientInfo == null) break;

                    servInfo = new DnsSdServiceInfo(cooked[2], null, null);
                    clientInfo.mChannel.sendMessage(NsdManager.REGISTER_SERVICE_SUCCEEDED,
                            id, 0, servInfo);
                    break;
                case NativeResponseCode.SERVICE_REGISTRATION_FAILED:
                    /* NNN regId errorCode */
                    if (DBG) Slog.d(TAG, "SERVICE_REGISTER_FAILED Raw: " + raw);
                    clientInfo = getClientByRegistration(id);
                    if (clientInfo == null) break;

                    clientInfo.mChannel.sendMessage(NsdManager.REGISTER_SERVICE_FAILED,
                            NsdManager.ERROR);
                    break;
                case NativeResponseCode.SERVICE_UPDATED:
                    /* NNN regId */
                    break;
                case NativeResponseCode.SERVICE_UPDATE_FAILED:
                    /* NNN regId errorCode */
                    break;
                case NativeResponseCode.SERVICE_RESOLVED:
                    /* NNN resolveId fullName hostName port txtlen txtdata */
                    if (DBG) Slog.d(TAG, "SERVICE_RESOLVED Raw: " + raw);
                    clientInfo = getClientByResolve(id);
                    if (clientInfo == null) break;

                    int index = cooked[2].indexOf(".");
                    if (index == -1) {
                        Slog.e(TAG, "Invalid service found " + raw);
                        break;
                    }
                    String name = cooked[2].substring(0, index);
                    String rest = cooked[2].substring(index);
                    String type = rest.replace(".local.", "");

                    clientInfo.mResolvedService = new DnsSdServiceInfo(name, type, null);
                    clientInfo.mResolvedService.setPort(Integer.parseInt(cooked[4]));

                    stopResolveService(id);
                    getAddrInfo(id, cooked[3]);
                    break;
                case NativeResponseCode.SERVICE_RESOLUTION_FAILED:
                case NativeResponseCode.SERVICE_GET_ADDR_FAILED:
                    /* NNN resolveId errorCode */
                    if (DBG) Slog.d(TAG, "SERVICE_RESOLVE_FAILED Raw: " + raw);
                    clientInfo = getClientByResolve(id);
                    if (clientInfo == null) break;

                    clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_FAILED,
                            NsdManager.ERROR);
                    break;
                case NativeResponseCode.SERVICE_GET_ADDR_SUCCESS:
                    /* NNN resolveId hostname ttl addr */
                    if (DBG) Slog.d(TAG, "SERVICE_GET_ADDR_SUCCESS Raw: " + raw);
                    clientInfo = getClientByResolve(id);
                    if (clientInfo == null || clientInfo.mResolvedService == null) break;

                    try {
                        clientInfo.mResolvedService.setHost(InetAddress.getByName(cooked[4]));
                        clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_SUCCEEDED,
                                clientInfo.mResolvedService);
                        clientInfo.mResolvedService = null;
                        clientInfo.mResolveId = INVALID_ID;
                    } catch (java.net.UnknownHostException e) {
                        clientInfo.mChannel.sendMessage(NsdManager.RESOLVE_SERVICE_FAILED,
                                NsdManager.ERROR);
                    }
                    stopGetAddrInfo(id);
                    break;
                default:
                    break;
            }
            return false;
        }
    }

    private boolean startMDnsDaemon() {
        if (DBG) Slog.d(TAG, "startMDnsDaemon");
        try {
            mNativeConnector.execute("mdnssd", "start-service");
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to start daemon" + e);
            return false;
        }
        return true;
    }

    private boolean stopMDnsDaemon() {
        if (DBG) Slog.d(TAG, "stopMDnsDaemon");
        try {
            mNativeConnector.execute("mdnssd", "stop-service");
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to start daemon" + e);
            return false;
        }
        return true;
    }

    private boolean registerService(int regId, DnsSdServiceInfo service) {
        if (DBG) Slog.d(TAG, "registerService: " + regId + " " + service);
        try {
            //Add txtlen and txtdata
            mNativeConnector.execute("mdnssd", "register", regId, service.getServiceName(),
                    service.getServiceType(), service.getPort());
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to execute registerService " + e);
            return false;
        }
        return true;
    }

    private boolean unregisterService(int regId) {
        if (DBG) Slog.d(TAG, "unregisterService: " + regId);
        try {
            mNativeConnector.execute("mdnssd", "stop-register", regId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to execute unregisterService " + e);
            return false;
        }
        return true;
    }

    private boolean updateService(int regId, DnsSdTxtRecord t) {
        if (DBG) Slog.d(TAG, "updateService: " + regId + " " + t);
        try {
            if (t == null) return false;
            mNativeConnector.execute("mdnssd", "update", regId, t.size(), t.getRawData());
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to updateServices " + e);
            return false;
        }
        return true;
    }

    private boolean discoverServices(int discoveryId, String serviceType) {
        if (DBG) Slog.d(TAG, "discoverServices: " + discoveryId + " " + serviceType);
        try {
            mNativeConnector.execute("mdnssd", "discover", discoveryId, serviceType);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to discoverServices " + e);
            return false;
        }
        return true;
    }

    private boolean stopServiceDiscovery(int discoveryId) {
        if (DBG) Slog.d(TAG, "stopServiceDiscovery: " + discoveryId);
        try {
            mNativeConnector.execute("mdnssd", "stop-discover", discoveryId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to stopServiceDiscovery " + e);
            return false;
        }
        return true;
    }

    private boolean resolveService(int resolveId, DnsSdServiceInfo service) {
        if (DBG) Slog.d(TAG, "resolveService: " + resolveId + " " + service);
        try {
            mNativeConnector.execute("mdnssd", "resolve", resolveId, service.getServiceName(),
                    service.getServiceType(), "local.");
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to resolveService " + e);
            return false;
        }
        return true;
    }

    private boolean stopResolveService(int resolveId) {
        if (DBG) Slog.d(TAG, "stopResolveService: " + resolveId);
        try {
            mNativeConnector.execute("mdnssd", "stop-resolve", resolveId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to stop resolve " + e);
            return false;
        }
        return true;
    }

    private boolean getAddrInfo(int resolveId, String hostname) {
        if (DBG) Slog.d(TAG, "getAdddrInfo: " + resolveId);
        try {
            mNativeConnector.execute("mdnssd", "getaddrinfo", resolveId, hostname);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to getAddrInfo " + e);
            return false;
        }
        return true;
    }

    private boolean stopGetAddrInfo(int resolveId) {
        if (DBG) Slog.d(TAG, "stopGetAdddrInfo: " + resolveId);
        try {
            mNativeConnector.execute("mdnssd", "stop-getaddrinfo", resolveId);
        } catch(NativeDaemonConnectorException e) {
            Slog.e(TAG, "Failed to stopGetAddrInfo " + e);
            return false;
        }
        return true;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ServiceDiscoverService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("Internal state:");
    }

    private ClientInfo getClientByDiscovery(int discoveryId) {
        for (ClientInfo c: mClients.values()) {
            if (c.mDiscoveryId == discoveryId) {
                return c;
            }
        }
        return null;
    }

    private ClientInfo getClientByResolve(int resolveId) {
        for (ClientInfo c: mClients.values()) {
            if (c.mResolveId == resolveId) {
                return c;
            }
        }
        return null;
    }

    private ClientInfo getClientByRegistration(int regId) {
        for (ClientInfo c: mClients.values()) {
            if (c.mRegisteredIds.contains(regId)) {
                return c;
            }
        }
        return null;
    }

    /* Information tracked per client */
    private class ClientInfo {

        private static final int MAX_REG = 5;
        private AsyncChannel mChannel;
        private Messenger mMessenger;
        private int mDiscoveryId;
        private int mResolveId;
        /* Remembers a resolved service until getaddrinfo completes */
        private DnsSdServiceInfo mResolvedService;
        private ArrayList<Integer> mRegisteredIds = new ArrayList<Integer>();

        private ClientInfo(AsyncChannel c, Messenger m) {
            mChannel = c;
            mMessenger = m;
            mDiscoveryId = mResolveId = INVALID_ID;
            if (DBG) Slog.d(TAG, "New client, channel: " + c + " messenger: " + m);
        }
    }
}
