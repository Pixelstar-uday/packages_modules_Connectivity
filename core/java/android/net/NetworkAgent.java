/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.util.AsyncChannel;
import com.android.internal.util.Protocol;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Utility class for handling for communicating between bearer-specific
 * code and ConnectivityService.
 *
 * A bearer may have more than one NetworkAgent if it can simultaneously
 * support separate networks (IMS / Internet / MMS Apns on cellular, or
 * perhaps connections with different SSID or P2P for Wi-Fi).
 *
 * @hide
 */
@SystemApi
public abstract class NetworkAgent {
    /**
     * The {@link Network} corresponding to this object.
     */
    @NonNull
    public final Network network;

    private final Handler mHandler;
    private volatile AsyncChannel mAsyncChannel;
    private final String LOG_TAG;
    private static final boolean DBG = true;
    private static final boolean VDBG = false;
    private final Context mContext;
    private final ArrayList<Message>mPreConnectedQueue = new ArrayList<Message>();
    private volatile long mLastBwRefreshTime = 0;
    private static final long BW_REFRESH_MIN_WIN_MS = 500;
    private boolean mBandwidthUpdateScheduled = false;
    private AtomicBoolean mBandwidthUpdatePending = new AtomicBoolean(false);

    /**
     * The ID of the {@link NetworkProvider} that created this object, or
     * {@link NetworkProvider#ID_NONE} if unknown.
     */
    public final int providerId;

    private static final int BASE = Protocol.BASE_NETWORK_AGENT;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform it of
     * suspected connectivity problems on its network.  The NetworkAgent
     * should take steps to verify and correct connectivity.
     * @hide
     */
    public static final int CMD_SUSPECT_BAD = BASE;

    /**
     * Sent by the NetworkAgent (note the EVENT vs CMD prefix) to
     * ConnectivityService to pass the current NetworkInfo (connection state).
     * Sent when the NetworkInfo changes, mainly due to change of state.
     * obj = NetworkInfo
     * @hide
     */
    public static final int EVENT_NETWORK_INFO_CHANGED = BASE + 1;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkCapabilties.
     * obj = NetworkCapabilities
     * @hide
     */
    public static final int EVENT_NETWORK_CAPABILITIES_CHANGED = BASE + 2;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * NetworkProperties.
     * obj = NetworkProperties
     * @hide
     */
    public static final int EVENT_NETWORK_PROPERTIES_CHANGED = BASE + 3;

    /**
     * Centralize the place where base network score, and network score scaling, will be
     * stored, so as we can consistently compare apple and oranges, or wifi, ethernet and LTE
     * @hide
     */
    public static final int WIFI_BASE_SCORE = 60;

    /**
     * Sent by the NetworkAgent to ConnectivityService to pass the current
     * network score.
     * obj = network score Integer
     * @hide
     */
    public static final int EVENT_NETWORK_SCORE_CHANGED = BASE + 4;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent of the
     * networks status - whether we could use the network or could not, due to
     * either a bad network configuration (no internet link) or captive portal.
     *
     * arg1 = either {@code VALID_NETWORK} or {@code INVALID_NETWORK}
     * obj = Bundle containing map from {@code REDIRECT_URL_KEY} to {@code String}
     *       representing URL that Internet probe was redirect to, if it was redirected,
     *       or mapping to {@code null} otherwise.
     * @hide
     */
    public static final int CMD_REPORT_NETWORK_STATUS = BASE + 7;


    /**
     * Network validation suceeded.
     * Corresponds to {@link NetworkCapabilities.NET_CAPABILITY_VALIDATED}.
     */
    public static final int VALIDATION_STATUS_VALID = 1;

    /**
     * Network validation was attempted and failed. This may be received more than once as
     * subsequent validation attempts are made.
     */
    public static final int VALIDATION_STATUS_NOT_VALID = 2;

    // TODO: remove.
    /** @hide */
    public static final int VALID_NETWORK = 1;
    /** @hide */
    public static final int INVALID_NETWORK = 2;

    /**
     * The key for the redirect URL in the Bundle argument of {@code CMD_REPORT_NETWORK_STATUS}.
     * @hide
     */
    public static String REDIRECT_URL_KEY = "redirect URL";

     /**
     * Sent by the NetworkAgent to ConnectivityService to indicate this network was
     * explicitly selected.  This should be sent before the NetworkInfo is marked
     * CONNECTED so it can be given special treatment at that time.
     *
     * obj = boolean indicating whether to use this network even if unvalidated
     * @hide
     */
    public static final int EVENT_SET_EXPLICITLY_SELECTED = BASE + 8;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent of
     * whether the network should in the future be used even if not validated.
     * This decision is made by the user, but it is the network transport's
     * responsibility to remember it.
     *
     * arg1 = 1 if true, 0 if false
     * @hide
     */
    public static final int CMD_SAVE_ACCEPT_UNVALIDATED = BASE + 9;

    /**
     * Sent by ConnectivityService to the NetworkAgent to inform the agent to pull
     * the underlying network connection for updated bandwidth information.
     * @hide
     */
    public static final int CMD_REQUEST_BANDWIDTH_UPDATE = BASE + 10;

    /**
     * Sent by ConnectivityService to the NetworkAgent to request that the specified packet be sent
     * periodically on the given interval.
     *
     *   arg1 = the slot number of the keepalive to start
     *   arg2 = interval in seconds
     *   obj = KeepalivePacketData object describing the data to be sent
     *
     * Also used internally by ConnectivityService / KeepaliveTracker, with different semantics.
     * @hide
     */
    public static final int CMD_START_SOCKET_KEEPALIVE = BASE + 11;

    /**
     * Requests that the specified keepalive packet be stopped.
     *
     * arg1 = slot number of the keepalive to stop.
     *
     * Also used internally by ConnectivityService / KeepaliveTracker, with different semantics.
     * @hide
     */
    public static final int CMD_STOP_SOCKET_KEEPALIVE = BASE + 12;

    /**
     * Sent by the NetworkAgent to ConnectivityService to provide status on a socket keepalive
     * request. This may either be the reply to a CMD_START_SOCKET_KEEPALIVE, or an asynchronous
     * error notification.
     *
     * This is also sent by KeepaliveTracker to the app's {@link SocketKeepalive},
     * so that the app's {@link SocketKeepalive.Callback} methods can be called.
     *
     * arg1 = slot number of the keepalive
     * arg2 = error code
     * @hide
     */
    public static final int EVENT_SOCKET_KEEPALIVE = BASE + 13;

    /**
     * Sent by ConnectivityService to inform this network transport of signal strength thresholds
     * that when crossed should trigger a system wakeup and a NetworkCapabilities update.
     *
     *   obj = int[] describing signal strength thresholds.
     * @hide
     */
    public static final int CMD_SET_SIGNAL_STRENGTH_THRESHOLDS = BASE + 14;

    /**
     * Sent by ConnectivityService to the NeworkAgent to inform the agent to avoid
     * automatically reconnecting to this network (e.g. via autojoin).  Happens
     * when user selects "No" option on the "Stay connected?" dialog box.
     * @hide
     */
    public static final int CMD_PREVENT_AUTOMATIC_RECONNECT = BASE + 15;

    /**
     * Sent by the KeepaliveTracker to NetworkAgent to add a packet filter.
     *
     * For TCP keepalive offloads, keepalive packets are sent by the firmware. However, because the
     * remote site will send ACK packets in response to the keepalive packets, the firmware also
     * needs to be configured to properly filter the ACKs to prevent the system from waking up.
     * This does not happen with UDP, so this message is TCP-specific.
     * arg1 = slot number of the keepalive to filter for.
     * obj = the keepalive packet to send repeatedly.
     * @hide
     */
    public static final int CMD_ADD_KEEPALIVE_PACKET_FILTER = BASE + 16;

    /**
     * Sent by the KeepaliveTracker to NetworkAgent to remove a packet filter. See
     * {@link #CMD_ADD_KEEPALIVE_PACKET_FILTER}.
     * arg1 = slot number of the keepalive packet filter to remove.
     * @hide
     */
    public static final int CMD_REMOVE_KEEPALIVE_PACKET_FILTER = BASE + 17;

    // TODO : remove these two constructors. They are a stopgap measure to help sheperding a number
    // of dependent changes that would conflict throughout the automerger graph. Having these
    // temporarily helps with the process of going through with all these dependent changes across
    // the entire tree.
    /** @hide TODO: decide which of these to expose. */
    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score) {
        this(looper, context, logTag, ni, nc, lp, score, null, NetworkProvider.ID_NONE);
    }

    /** @hide TODO: decide which of these to expose. */
    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score, NetworkAgentConfig config) {
        this(looper, context, logTag, ni, nc, lp, score, config, NetworkProvider.ID_NONE);
    }

    /** @hide TODO: decide which of these to expose. */
    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score, int providerId) {
        this(looper, context, logTag, ni, nc, lp, score, null, providerId);
    }

    /** @hide TODO: decide which of these to expose. */
    public NetworkAgent(Looper looper, Context context, String logTag, NetworkInfo ni,
            NetworkCapabilities nc, LinkProperties lp, int score, NetworkAgentConfig config,
            int providerId) {
        mHandler = new NetworkAgentHandler(looper);
        LOG_TAG = logTag;
        mContext = context;
        this.providerId = providerId;
        if (ni == null || nc == null || lp == null) {
            throw new IllegalArgumentException();
        }

        if (VDBG) log("Registering NetworkAgent");
        ConnectivityManager cm = (ConnectivityManager)mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        network = cm.registerNetworkAgent(new Messenger(mHandler), new NetworkInfo(ni),
                new LinkProperties(lp), new NetworkCapabilities(nc), score, config,
                providerId);
    }

    private class NetworkAgentHandler extends Handler {
        NetworkAgentHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION: {
                    if (mAsyncChannel != null) {
                        log("Received new connection while already connected!");
                    } else {
                        if (VDBG) log("NetworkAgent fully connected");
                        AsyncChannel ac = new AsyncChannel();
                        ac.connected(null, this, msg.replyTo);
                        ac.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                AsyncChannel.STATUS_SUCCESSFUL);
                        synchronized (mPreConnectedQueue) {
                            mAsyncChannel = ac;
                            for (Message m : mPreConnectedQueue) {
                                ac.sendMessage(m);
                            }
                            mPreConnectedQueue.clear();
                        }
                    }
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECT: {
                    if (VDBG) log("CMD_CHANNEL_DISCONNECT");
                    if (mAsyncChannel != null) mAsyncChannel.disconnect();
                    break;
                }
                case AsyncChannel.CMD_CHANNEL_DISCONNECTED: {
                    if (DBG) log("NetworkAgent channel lost");
                    // let the client know CS is done with us.
                    onNetworkUnwanted();
                    synchronized (mPreConnectedQueue) {
                        mAsyncChannel = null;
                    }
                    break;
                }
                case CMD_SUSPECT_BAD: {
                    log("Unhandled Message " + msg);
                    break;
                }
                case CMD_REQUEST_BANDWIDTH_UPDATE: {
                    long currentTimeMs = System.currentTimeMillis();
                    if (VDBG) {
                        log("CMD_REQUEST_BANDWIDTH_UPDATE request received.");
                    }
                    if (currentTimeMs >= (mLastBwRefreshTime + BW_REFRESH_MIN_WIN_MS)) {
                        mBandwidthUpdateScheduled = false;
                        if (!mBandwidthUpdatePending.getAndSet(true)) {
                            onBandwidthUpdateRequested();
                        }
                    } else {
                        // deliver the request at a later time rather than discard it completely.
                        if (!mBandwidthUpdateScheduled) {
                            long waitTime = mLastBwRefreshTime + BW_REFRESH_MIN_WIN_MS
                                    - currentTimeMs + 1;
                            mBandwidthUpdateScheduled = sendEmptyMessageDelayed(
                                    CMD_REQUEST_BANDWIDTH_UPDATE, waitTime);
                        }
                    }
                    break;
                }
                case CMD_REPORT_NETWORK_STATUS: {
                    String redirectUrl = ((Bundle) msg.obj).getString(REDIRECT_URL_KEY);
                    if (VDBG) {
                        log("CMD_REPORT_NETWORK_STATUS("
                                + (msg.arg1 == VALID_NETWORK ? "VALID, " : "INVALID, ")
                                + redirectUrl);
                    }
                    onValidationStatus(msg.arg1 /* status */, redirectUrl);
                    break;
                }
                case CMD_SAVE_ACCEPT_UNVALIDATED: {
                    onSaveAcceptUnvalidated(msg.arg1 != 0);
                    break;
                }
                case CMD_START_SOCKET_KEEPALIVE: {
                    onStartSocketKeepalive(msg.arg1 /* slot */, msg.arg2 /* interval */,
                            (KeepalivePacketData) msg.obj /* packet */);
                    break;
                }
                case CMD_STOP_SOCKET_KEEPALIVE: {
                    onStopSocketKeepalive(msg.arg1 /* slot */);
                    break;
                }

                case CMD_SET_SIGNAL_STRENGTH_THRESHOLDS: {
                    ArrayList<Integer> thresholds =
                            ((Bundle) msg.obj).getIntegerArrayList("thresholds");
                    // TODO: Change signal strength thresholds API to use an ArrayList<Integer>
                    // rather than convert to int[].
                    int[] intThresholds = new int[(thresholds != null) ? thresholds.size() : 0];
                    for (int i = 0; i < intThresholds.length; i++) {
                        intThresholds[i] = thresholds.get(i);
                    }
                    onSignalStrengthThresholdsUpdated(intThresholds);
                    break;
                }
                case CMD_PREVENT_AUTOMATIC_RECONNECT: {
                    onAutomaticReconnectDisabled();
                    break;
                }
                case CMD_ADD_KEEPALIVE_PACKET_FILTER: {
                    onAddKeepalivePacketFilter(msg.arg1 /* slot */,
                            (KeepalivePacketData) msg.obj /* packet */);
                    break;
                }
                case CMD_REMOVE_KEEPALIVE_PACKET_FILTER: {
                    onRemoveKeepalivePacketFilter(msg.arg1 /* slot */);
                    break;
                }
            }
        }
    }

    private void queueOrSendMessage(int what, Object obj) {
        queueOrSendMessage(what, 0, 0, obj);
    }

    private void queueOrSendMessage(int what, int arg1, int arg2) {
        queueOrSendMessage(what, arg1, arg2, null);
    }

    private void queueOrSendMessage(int what, int arg1, int arg2, Object obj) {
        Message msg = Message.obtain();
        msg.what = what;
        msg.arg1 = arg1;
        msg.arg2 = arg2;
        msg.obj = obj;
        queueOrSendMessage(msg);
    }

    private void queueOrSendMessage(Message msg) {
        synchronized (mPreConnectedQueue) {
            if (mAsyncChannel != null) {
                mAsyncChannel.sendMessage(msg);
            } else {
                mPreConnectedQueue.add(msg);
            }
        }
    }

    /**
     * Must be called by the agent when the network's {@link LinkProperties} change.
     * @param linkProperties the new LinkProperties.
     */
    public void sendLinkProperties(@NonNull LinkProperties linkProperties) {
        queueOrSendMessage(EVENT_NETWORK_PROPERTIES_CHANGED, new LinkProperties(linkProperties));
    }

    /**
     * Must be called by the agent when it has a new NetworkInfo object.
     * @hide TODO: expose something better.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public void sendNetworkInfo(NetworkInfo networkInfo) {
        queueOrSendMessage(EVENT_NETWORK_INFO_CHANGED, new NetworkInfo(networkInfo));
    }

    /**
     * Must be called by the agent when the network's {@link NetworkCapabilities} change.
     * @param networkCapabilities the new NetworkCapabilities.
     */
    public void sendNetworkCapabilities(@NonNull NetworkCapabilities networkCapabilities) {
        mBandwidthUpdatePending.set(false);
        mLastBwRefreshTime = System.currentTimeMillis();
        queueOrSendMessage(EVENT_NETWORK_CAPABILITIES_CHANGED,
                new NetworkCapabilities(networkCapabilities));
    }

    /**
     * Must be called by the agent to update the score of this network.
     * @param score the new score.
     */
    public void sendNetworkScore(int score) {
        if (score < 0) {
            throw new IllegalArgumentException("Score must be >= 0");
        }
        final NetworkScore ns = new NetworkScore();
        ns.putIntExtension(NetworkScore.LEGACY_SCORE, score);
        updateScore(ns);
    }

    /**
     * Must be called by the agent when it has a new {@link NetworkScore} for this network.
     * @param ns the new score.
     * @hide TODO: unhide the NetworkScore class, and rename to sendNetworkScore.
     */
    public void updateScore(@NonNull NetworkScore ns) {
        queueOrSendMessage(EVENT_NETWORK_SCORE_CHANGED, new NetworkScore(ns));
    }

    /**
     * Must be called by the agent to indicate this network was manually selected by the user.
     * This should be called before the NetworkInfo is marked CONNECTED so that this
     * Network can be given special treatment at that time. If {@code acceptUnvalidated} is
     * {@code true}, then the system will switch to this network. If it is {@code false} and the
     * network cannot be validated, the system will ask the user whether to switch to this network.
     * If the user confirms and selects "don't ask again", then the system will call
     * {@link #saveAcceptUnvalidated} to persist the user's choice. Thus, if the transport ever
     * calls this method with {@code acceptUnvalidated} set to {@code false}, it must also implement
     * {@link #saveAcceptUnvalidated} to respect the user's choice.
     * @hide should move to NetworkAgentConfig.
     */
    public void explicitlySelected(boolean acceptUnvalidated) {
        explicitlySelected(true /* explicitlySelected */, acceptUnvalidated);
    }

    /**
     * Must be called by the agent to indicate whether the network was manually selected by the
     * user. This should be called before the network becomes connected, so it can be given
     * special treatment when it does.
     *
     * If {@code explicitlySelected} is {@code true}, and {@code acceptUnvalidated} is {@code true},
     * then the system will switch to this network. If {@code explicitlySelected} is {@code true}
     * and {@code acceptUnvalidated} is {@code false}, and the  network cannot be validated, the
     * system will ask the user whether to switch to this network.  If the user confirms and selects
     * "don't ask again", then the system will call {@link #saveAcceptUnvalidated} to persist the
     * user's choice. Thus, if the transport ever calls this method with {@code explicitlySelected}
     * set to {@code true} and {@code acceptUnvalidated} set to {@code false}, it must also
     * implement {@link #saveAcceptUnvalidated} to respect the user's choice.
     *
     * If  {@code explicitlySelected} is {@code false} and {@code acceptUnvalidated} is
     * {@code true}, the system will interpret this as the user having accepted partial connectivity
     * on this network. Thus, the system will switch to the network and consider it validated even
     * if it only provides partial connectivity, but the network is not otherwise treated specially.
     * @hide should move to NetworkAgentConfig.
     */
    public void explicitlySelected(boolean explicitlySelected, boolean acceptUnvalidated) {
        queueOrSendMessage(EVENT_SET_EXPLICITLY_SELECTED,
                explicitlySelected ? 1 : 0,
                acceptUnvalidated ? 1 : 0);
    }

    /**
     * Called when ConnectivityService has indicated they no longer want this network.
     * The parent factory should (previously) have received indication of the change
     * as well, either canceling NetworkRequests or altering their score such that this
     * network won't be immediately requested again.
     */
    public void onNetworkUnwanted() {
        unwanted();
    }
    /** @hide TODO delete once subclasses have moved to onNetworkUnwanted. */
    protected void unwanted() {
    }

    /**
     * Called when ConnectivityService request a bandwidth update. The parent factory
     * shall try to overwrite this method and produce a bandwidth update if capable.
     */
    public void onBandwidthUpdateRequested() {
        pollLceData();
    }
    /** @hide TODO delete once subclasses have moved to onBandwidthUpdateRequested. */
    protected void pollLceData() {
    }

    /**
     * Called when the system determines the usefulness of this network.
     *
     * The system attempts to validate Internet connectivity on networks that provide the
     * {@NetworkCapabilities#NET_CAPABILITY_INTERNET} capability.
     *
     * Currently there are two possible values:
     * {@code VALIDATION_STATUS_VALID} if Internet connectivity was validated,
     * {@code VALIDATION_STATUS_NOT_VALID} if Internet connectivity was not validated.
     *
     * This may be called multiple times as network status changes, or if there are multiple
     * subsequent attempts to validate connectivity that fail.
     *
     * @param status one of {@code VALIDATION_STATUS_VALID} or {@code VALIDATION_STATUS_NOT_VALID}.
     * @param redirectUrl If Internet connectivity is being redirected (e.g., on a captive portal),
     *        this is the destination the probes are being redirected to, otherwise {@code null}.
     */
    public void onValidationStatus(int status, @Nullable String redirectUrl) {
        networkStatus(status, redirectUrl);
    }
    /** @hide TODO delete once subclasses have moved to onValidationStatus */
    protected void networkStatus(int status, String redirectUrl) {
    }


    /**
     * Called when the user asks to remember the choice to use this network even if unvalidated.
     * The transport is responsible for remembering the choice, and the next time the user connects
     * to the network, should explicitlySelected with {@code acceptUnvalidated} set to {@code true}.
     * This method will only be called if {@link #explicitlySelected} was called with
     * {@code acceptUnvalidated} set to {@code false}.
     * @param accept whether the user wants to use the network even if unvalidated.
     */
    public void onSaveAcceptUnvalidated(boolean accept) {
        saveAcceptUnvalidated(accept);
    }
    /** @hide TODO delete once subclasses have moved to onSaveAcceptUnvalidated */
    protected void saveAcceptUnvalidated(boolean accept) {
    }

    /**
     * Requests that the network hardware send the specified packet at the specified interval.
     *
     * @param slot the hardware slot on which to start the keepalive.
     * @param intervalSeconds the interval between packets
     * @param packet the packet to send.
     */
    public void onStartSocketKeepalive(int slot, int intervalSeconds,
            @NonNull KeepalivePacketData packet) {
        Message msg = mHandler.obtainMessage(CMD_START_SOCKET_KEEPALIVE, slot, intervalSeconds,
                packet);
        startSocketKeepalive(msg);
        msg.recycle();
    }
    /** @hide TODO delete once subclasses have moved to onStartSocketKeepalive */
    protected void startSocketKeepalive(Message msg) {
        onSocketKeepaliveEvent(msg.arg1, SocketKeepalive.ERROR_UNSUPPORTED);
    }

    /**
     * Requests that the network hardware stop a previously-started keepalive.
     *
     * @param slot the hardware slot on which to stop the keepalive.
     */
    public void onStopSocketKeepalive(int slot) {
        Message msg = mHandler.obtainMessage(CMD_STOP_SOCKET_KEEPALIVE, slot, 0, null);
        stopSocketKeepalive(msg);
        msg.recycle();
    }
    /** @hide TODO delete once subclasses have moved to onStopSocketKeepalive */
    protected void stopSocketKeepalive(Message msg) {
        onSocketKeepaliveEvent(msg.arg1, SocketKeepalive.ERROR_UNSUPPORTED);
    }

    /**
     * Must be called by the agent when a socket keepalive event occurs.
     *
     * @param slot the hardware slot on which the event occurred.
     * @param event the event that occurred.
     */
    public void sendSocketKeepaliveEvent(int slot, int event) {
        queueOrSendMessage(EVENT_SOCKET_KEEPALIVE, slot, event);
    }
    /** @hide TODO delete once callers have moved to sendSocketKeepaliveEvent */
    public void onSocketKeepaliveEvent(int slot, int reason) {
        sendSocketKeepaliveEvent(slot, reason);
    }

    /**
     * Called by ConnectivityService to add specific packet filter to network hardware to block
     * replies (e.g., TCP ACKs) matching the sent keepalive packets. Implementations that support
     * this feature must override this method.
     *
     * @param slot the hardware slot on which the keepalive should be sent.
     * @param packet the packet that is being sent.
     */
    public void onAddKeepalivePacketFilter(int slot, @NonNull KeepalivePacketData packet) {
        Message msg = mHandler.obtainMessage(CMD_ADD_KEEPALIVE_PACKET_FILTER, slot, 0, packet);
        addKeepalivePacketFilter(msg);
        msg.recycle();
    }
    /** @hide TODO delete once subclasses have moved to onAddKeepalivePacketFilter */
    protected void addKeepalivePacketFilter(Message msg) {
    }

    /**
     * Called by ConnectivityService to remove a packet filter installed with
     * {@link #addKeepalivePacketFilter(Message)}. Implementations that support this feature
     * must override this method.
     *
     * @param slot the hardware slot on which the keepalive is being sent.
     */
    public void onRemoveKeepalivePacketFilter(int slot) {
        Message msg = mHandler.obtainMessage(CMD_REMOVE_KEEPALIVE_PACKET_FILTER, slot, 0, null);
        removeKeepalivePacketFilter(msg);
        msg.recycle();
    }
    /** @hide TODO delete once subclasses have moved to onRemoveKeepalivePacketFilter */
    protected void removeKeepalivePacketFilter(Message msg) {
    }

    /**
     * Called by ConnectivityService to inform this network transport of signal strength thresholds
     * that when crossed should trigger a system wakeup and a NetworkCapabilities update.
     *
     * @param thresholds the array of thresholds that should trigger wakeups.
     */
    public void onSignalStrengthThresholdsUpdated(@NonNull int[] thresholds) {
        setSignalStrengthThresholds(thresholds);
    }
    /** @hide TODO delete once subclasses have moved to onSetSignalStrengthThresholds */
    protected void setSignalStrengthThresholds(int[] thresholds) {
    }

    /**
     * Called when the user asks to not stay connected to this network because it was found to not
     * provide Internet access.  Usually followed by call to {@code unwanted}.  The transport is
     * responsible for making sure the device does not automatically reconnect to the same network
     * after the {@code unwanted} call.
     */
    public void onAutomaticReconnectDisabled() {
        preventAutomaticReconnect();
    }
    /** @hide TODO delete once subclasses have moved to onAutomaticReconnectDisabled */
    protected void preventAutomaticReconnect() {
    }

    /** @hide */
    protected void log(String s) {
        Log.d(LOG_TAG, "NetworkAgent: " + s);
    }
}
