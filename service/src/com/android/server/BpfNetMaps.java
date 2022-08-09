/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.net.ConnectivityManager.FIREWALL_CHAIN_DOZABLE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_LOW_POWER_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_1;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_2;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_OEM_DENY_3;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_POWERSAVE;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_RESTRICTED;
import static android.net.ConnectivityManager.FIREWALL_CHAIN_STANDBY;
import static android.net.ConnectivityManager.FIREWALL_RULE_ALLOW;
import static android.net.ConnectivityManager.FIREWALL_RULE_DENY;
import static android.system.OsConstants.EINVAL;
import static android.system.OsConstants.ENODEV;
import static android.system.OsConstants.ENOENT;
import static android.system.OsConstants.EOPNOTSUPP;

import android.content.Context;
import android.net.INetd;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.provider.DeviceConfig;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;
import com.android.net.module.util.BpfMap;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.net.module.util.Struct.U32;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * BpfNetMaps is responsible for providing traffic controller relevant functionality.
 *
 * {@hide}
 */
public class BpfNetMaps {
    private static final boolean PRE_T = !SdkLevel.isAtLeastT();
    static {
        if (!PRE_T) {
            System.loadLibrary("service-connectivity");
        }
    }

    private static final String TAG = "BpfNetMaps";
    private final INetd mNetd;
    private final Dependencies mDeps;
    // Use legacy netd for releases before T.
    private static boolean sInitialized = false;

    private static Boolean sEnableJavaBpfMap = null;
    private static final String BPF_NET_MAPS_ENABLE_JAVA_BPF_MAP =
            "bpf_net_maps_enable_java_bpf_map";

    // Lock for sConfigurationMap entry for UID_RULES_CONFIGURATION_KEY.
    // This entry is not accessed by others.
    // BpfNetMaps acquires this lock while sequence of read, modify, and write.
    private static final Object sUidRulesConfigBpfMapLock = new Object();

    private static final String CONFIGURATION_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_configuration_map";
    private static final String UID_OWNER_MAP_PATH =
            "/sys/fs/bpf/netd_shared/map_netd_uid_owner_map";
    private static final U32 UID_RULES_CONFIGURATION_KEY = new U32(0);
    private static final U32 CURRENT_STATS_MAP_CONFIGURATION_KEY = new U32(1);
    private static final long UID_RULES_DEFAULT_CONFIGURATION = 0;
    private static final long STATS_SELECT_MAP_A = 0;
    private static final long STATS_SELECT_MAP_B = 1;

    private static BpfMap<U32, U32> sConfigurationMap = null;
    // BpfMap for UID_OWNER_MAP_PATH. This map is not accessed by others.
    private static BpfMap<U32, UidOwnerValue> sUidOwnerMap = null;

    // LINT.IfChange(match_type)
    @VisibleForTesting public static final long NO_MATCH = 0;
    @VisibleForTesting public static final long HAPPY_BOX_MATCH = (1 << 0);
    @VisibleForTesting public static final long PENALTY_BOX_MATCH = (1 << 1);
    @VisibleForTesting public static final long DOZABLE_MATCH = (1 << 2);
    @VisibleForTesting public static final long STANDBY_MATCH = (1 << 3);
    @VisibleForTesting public static final long POWERSAVE_MATCH = (1 << 4);
    @VisibleForTesting public static final long RESTRICTED_MATCH = (1 << 5);
    @VisibleForTesting public static final long LOW_POWER_STANDBY_MATCH = (1 << 6);
    @VisibleForTesting public static final long IIF_MATCH = (1 << 7);
    @VisibleForTesting public static final long LOCKDOWN_VPN_MATCH = (1 << 8);
    @VisibleForTesting public static final long OEM_DENY_1_MATCH = (1 << 9);
    @VisibleForTesting public static final long OEM_DENY_2_MATCH = (1 << 10);
    @VisibleForTesting public static final long OEM_DENY_3_MATCH = (1 << 11);
    // LINT.ThenChange(packages/modules/Connectivity/bpf_progs/bpf_shared.h)

    /**
     * Set sEnableJavaBpfMap for test.
     */
    @VisibleForTesting
    public static void setEnableJavaBpfMapForTest(boolean enable) {
        sEnableJavaBpfMap = enable;
    }

    /**
     * Set configurationMap for test.
     */
    @VisibleForTesting
    public static void setConfigurationMapForTest(BpfMap<U32, U32> configurationMap) {
        sConfigurationMap = configurationMap;
    }

    /**
     * Set uidOwnerMap for test.
     */
    @VisibleForTesting
    public static void setUidOwnerMapForTest(BpfMap<U32, UidOwnerValue> uidOwnerMap) {
        sUidOwnerMap = uidOwnerMap;
    }

    private static BpfMap<U32, U32> getConfigurationMap() {
        try {
            return new BpfMap<>(
                    CONFIGURATION_MAP_PATH, BpfMap.BPF_F_RDWR, U32.class, U32.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open netd configuration map", e);
        }
    }

    private static BpfMap<U32, UidOwnerValue> getUidOwnerMap() {
        try {
            return new BpfMap<>(
                    UID_OWNER_MAP_PATH, BpfMap.BPF_F_RDWR, U32.class, UidOwnerValue.class);
        } catch (ErrnoException e) {
            throw new IllegalStateException("Cannot open uid owner map", e);
        }
    }

    private static void initBpfMaps() {
        if (sConfigurationMap == null) {
            sConfigurationMap = getConfigurationMap();
        }
        try {
            sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY,
                    new U32(UID_RULES_DEFAULT_CONFIGURATION));
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to initialize uid rules configuration", e);
        }
        try {
            sConfigurationMap.updateEntry(CURRENT_STATS_MAP_CONFIGURATION_KEY,
                    new U32(STATS_SELECT_MAP_A));
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to initialize current stats configuration", e);
        }

        if (sUidOwnerMap == null) {
            sUidOwnerMap = getUidOwnerMap();
        }
        try {
            sUidOwnerMap.clear();
        } catch (ErrnoException e) {
            throw new IllegalStateException("Failed to initialize uid owner map", e);
        }
    }

    /**
     * Initializes the class if it is not already initialized. This method will open maps but not
     * cause any other effects. This method may be called multiple times on any thread.
     */
    private static synchronized void ensureInitialized(final Context context) {
        if (sInitialized) return;
        if (sEnableJavaBpfMap == null) {
            sEnableJavaBpfMap = DeviceConfigUtils.isFeatureEnabled(context,
                    DeviceConfig.NAMESPACE_TETHERING, BPF_NET_MAPS_ENABLE_JAVA_BPF_MAP,
                    SdkLevel.isAtLeastU() /* defaultValue */);
        }
        Log.d(TAG, "BpfNetMaps is initialized with sEnableJavaBpfMap=" + sEnableJavaBpfMap);

        initBpfMaps();
        native_init();
        sInitialized = true;
    }

    /**
     * Dependencies of BpfNetMaps, for injection in tests.
     */
    @VisibleForTesting
    public static class Dependencies {
        /**
         * Get interface index.
         */
        public int getIfIndex(final String ifName) {
            return Os.if_nametoindex(ifName);
        }
    }

    /** Constructor used after T that doesn't need to use netd anymore. */
    public BpfNetMaps(final Context context) {
        this(context, null);

        if (PRE_T) throw new IllegalArgumentException("BpfNetMaps need to use netd before T");
    }

    public BpfNetMaps(final Context context, final INetd netd) {
        this(context, netd, new Dependencies());
    }

    @VisibleForTesting
    public BpfNetMaps(final Context context, final INetd netd, final Dependencies deps) {
        if (!PRE_T) {
            ensureInitialized(context);
        }
        mNetd = netd;
        mDeps = deps;
    }

    /**
     * Get corresponding match from firewall chain.
     */
    @VisibleForTesting
    public long getMatchByFirewallChain(final int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_DOZABLE:
                return DOZABLE_MATCH;
            case FIREWALL_CHAIN_STANDBY:
                return STANDBY_MATCH;
            case FIREWALL_CHAIN_POWERSAVE:
                return POWERSAVE_MATCH;
            case FIREWALL_CHAIN_RESTRICTED:
                return RESTRICTED_MATCH;
            case FIREWALL_CHAIN_LOW_POWER_STANDBY:
                return LOW_POWER_STANDBY_MATCH;
            case FIREWALL_CHAIN_OEM_DENY_1:
                return OEM_DENY_1_MATCH;
            case FIREWALL_CHAIN_OEM_DENY_2:
                return OEM_DENY_2_MATCH;
            case FIREWALL_CHAIN_OEM_DENY_3:
                return OEM_DENY_3_MATCH;
            default:
                throw new ServiceSpecificException(EINVAL, "Invalid firewall chain: " + chain);
        }
    }

    /**
     * Get if the chain is allow list or not.
     *
     * ALLOWLIST means the firewall denies all by default, uids must be explicitly allowed
     * DENYLIST means the firewall allows all by default, uids must be explicitly denyed
     */
    @VisibleForTesting
    public boolean isFirewallAllowList(final int chain) {
        switch (chain) {
            case FIREWALL_CHAIN_DOZABLE:
            case FIREWALL_CHAIN_POWERSAVE:
            case FIREWALL_CHAIN_RESTRICTED:
            case FIREWALL_CHAIN_LOW_POWER_STANDBY:
                return true;
            case FIREWALL_CHAIN_STANDBY:
            case FIREWALL_CHAIN_OEM_DENY_1:
            case FIREWALL_CHAIN_OEM_DENY_2:
            case FIREWALL_CHAIN_OEM_DENY_3:
                return false;
            default:
                throw new ServiceSpecificException(EINVAL, "Invalid firewall chain: " + chain);
        }
    }

    private void maybeThrow(final int err, final String msg) {
        if (err != 0) {
            throw new ServiceSpecificException(err, msg + ": " + Os.strerror(err));
        }
    }

    private void throwIfPreT(final String msg) {
        if (PRE_T) {
            throw new UnsupportedOperationException(msg);
        }
    }

    private void removeRule(final int uid, final long match, final String caller) {
        try {
            synchronized (sUidOwnerMap) {
                final UidOwnerValue oldMatch = sUidOwnerMap.getValue(new U32(uid));

                if (oldMatch == null) {
                    throw new ServiceSpecificException(ENOENT,
                            "sUidOwnerMap does not have entry for uid: " + uid);
                }

                final UidOwnerValue newMatch = new UidOwnerValue(
                        (match == IIF_MATCH) ? 0 : oldMatch.iif,
                        oldMatch.rule & ~match
                );

                if (newMatch.rule == 0) {
                    sUidOwnerMap.deleteEntry(new U32(uid));
                } else {
                    sUidOwnerMap.updateEntry(new U32(uid), newMatch);
                }
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    caller + " failed to remove rule: " + Os.strerror(e.errno));
        }
    }

    private void addRule(final int uid, final long match, final long iif, final String caller) {
        if (match != IIF_MATCH && iif != 0) {
            throw new ServiceSpecificException(EINVAL,
                    "Non-interface match must have zero interface index");
        }

        try {
            synchronized (sUidOwnerMap) {
                final UidOwnerValue oldMatch = sUidOwnerMap.getValue(new U32(uid));

                final UidOwnerValue newMatch;
                if (oldMatch != null) {
                    newMatch = new UidOwnerValue(
                            (match == IIF_MATCH) ? iif : oldMatch.iif,
                            oldMatch.rule | match
                    );
                } else {
                    newMatch = new UidOwnerValue(
                            iif,
                            match
                    );
                }
                sUidOwnerMap.updateEntry(new U32(uid), newMatch);
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    caller + " failed to add rule: " + Os.strerror(e.errno));
        }
    }

    private void addRule(final int uid, final long match, final String caller) {
        addRule(uid, match, 0 /* iif */, caller);
    }

    /**
     * Add naughty app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addNaughtyApp(final int uid) {
        throwIfPreT("addNaughtyApp is not available on pre-T devices");
        addRule(uid, PENALTY_BOX_MATCH, "addNaughtyApp");
    }

    /**
     * Remove naughty app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeNaughtyApp(final int uid) {
        throwIfPreT("removeNaughtyApp is not available on pre-T devices");

        if (sEnableJavaBpfMap) {
            removeRule(uid, PENALTY_BOX_MATCH, "removeNaughtyApp");
        } else {
            final int err = native_removeNaughtyApp(uid);
            maybeThrow(err, "Unable to remove naughty app");
        }
    }

    /**
     * Add nice app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addNiceApp(final int uid) {
        throwIfPreT("addNiceApp is not available on pre-T devices");
        addRule(uid, HAPPY_BOX_MATCH, "addNiceApp");
    }

    /**
     * Remove nice app bandwidth rule for specific app
     *
     * @param uid uid of target app
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeNiceApp(final int uid) {
        throwIfPreT("removeNiceApp is not available on pre-T devices");
        removeRule(uid, HAPPY_BOX_MATCH, "removeNiceApp");
    }

    /**
     * Set target firewall child chain
     *
     * @param childChain target chain to enable
     * @param enable     whether to enable or disable child chain.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setChildChain(final int childChain, final boolean enable) {
        throwIfPreT("setChildChain is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        try {
            synchronized (sUidRulesConfigBpfMapLock) {
                final U32 config = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
                final long newConfig = enable ? (config.val | match) : (config.val & ~match);
                sConfigurationMap.updateEntry(UID_RULES_CONFIGURATION_KEY, new U32(newConfig));
            }
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to set child chain: " + Os.strerror(e.errno));
        }
    }

    /**
     * Get the specified firewall chain's status.
     *
     * @param childChain target chain
     * @return {@code true} if chain is enabled, {@code false} if chain is not enabled.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public boolean isChainEnabled(final int childChain) {
        throwIfPreT("isChainEnabled is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        try {
            final U32 config = sConfigurationMap.getValue(UID_RULES_CONFIGURATION_KEY);
            return (config.val & match) != 0;
        } catch (ErrnoException e) {
            throw new ServiceSpecificException(e.errno,
                    "Unable to get firewall chain status: " + Os.strerror(e.errno));
        }
    }

    /**
     * Replaces the contents of the specified UID-based firewall chain.
     * Enables the chain for specified uids and disables the chain for non-specified uids.
     *
     * @param chain       Target chain.
     * @param uids        The list of UIDs to allow/deny.
     * @throws UnsupportedOperationException if called on pre-T devices.
     * @throws IllegalArgumentException if {@code chain} is not a valid chain.
     */
    public void replaceUidChain(final int chain, final int[] uids) {
        throwIfPreT("replaceUidChain is not available on pre-T devices");

        final long match;
        try {
            match = getMatchByFirewallChain(chain);
        } catch (ServiceSpecificException e) {
            // Throws IllegalArgumentException to keep the behavior of
            // ConnectivityManager#replaceFirewallChain API
            throw new IllegalArgumentException("Invalid firewall chain: " + chain);
        }
        final Set<Integer> uidSet = Arrays.stream(uids).boxed().collect(Collectors.toSet());
        final Set<Integer> uidSetToRemoveRule = new HashSet<>();
        try {
            synchronized (sUidOwnerMap) {
                sUidOwnerMap.forEach((uid, config) -> {
                    // config could be null if there is a concurrent entry deletion.
                    // http://b/220084230.
                    if (config != null
                            && !uidSet.contains((int) uid.val) && (config.rule & match) != 0) {
                        uidSetToRemoveRule.add((int) uid.val);
                    }
                });

                for (final int uid : uidSetToRemoveRule) {
                    removeRule(uid, match, "replaceUidChain");
                }
                for (final int uid : uids) {
                    addRule(uid, match, "replaceUidChain");
                }
            }
        } catch (ErrnoException | ServiceSpecificException e) {
            Log.e(TAG, "replaceUidChain failed: " + e);
        }
    }

    /**
     * Set firewall rule for uid
     *
     * @param childChain   target chain
     * @param uid          uid to allow/deny
     * @param firewallRule either FIREWALL_RULE_ALLOW or FIREWALL_RULE_DENY
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void setUidRule(final int childChain, final int uid, final int firewallRule) {
        throwIfPreT("setUidRule is not available on pre-T devices");

        final long match = getMatchByFirewallChain(childChain);
        final boolean isAllowList = isFirewallAllowList(childChain);
        final boolean add = (firewallRule == FIREWALL_RULE_ALLOW && isAllowList)
                || (firewallRule == FIREWALL_RULE_DENY && !isAllowList);

        if (add) {
            addRule(uid, match, "setUidRule");
        } else {
            removeRule(uid, match, "setUidRule");
        }
    }

    /**
     * Add ingress interface filtering rules to a list of UIDs
     *
     * For a given uid, once a filtering rule is added, the kernel will only allow packets from the
     * allowed interface and loopback to be sent to the list of UIDs.
     *
     * Calling this method on one or more UIDs with an existing filtering rule but a different
     * interface name will result in the filtering rule being updated to allow the new interface
     * instead. Otherwise calling this method will not affect existing rules set on other UIDs.
     *
     * @param ifName the name of the interface on which the filtering rules will allow packets to
     *               be received.
     * @param uids   an array of UIDs which the filtering rules will be set
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void addUidInterfaceRules(final String ifName, final int[] uids) throws RemoteException {
        if (PRE_T) {
            mNetd.firewallAddUidInterfaceRules(ifName, uids);
            return;
        }
        // Null ifName is a wildcard to allow apps to receive packets on all interfaces and ifIndex
        // is set to 0.
        final int ifIndex;
        if (ifName == null) {
            ifIndex = 0;
        } else {
            ifIndex = mDeps.getIfIndex(ifName);
            if (ifIndex == 0) {
                throw new ServiceSpecificException(ENODEV,
                        "Failed to get index of interface " + ifName);
            }
        }
        for (final int uid: uids) {
            try {
                addRule(uid, IIF_MATCH, ifIndex, "addUidInterfaceRules");
            } catch (ServiceSpecificException e) {
                Log.e(TAG, "addRule failed uid=" + uid + " ifName=" + ifName + ", " + e);
            }
        }
    }

    /**
     * Remove ingress interface filtering rules from a list of UIDs
     *
     * Clear the ingress interface filtering rules from the list of UIDs which were previously set
     * by addUidInterfaceRules(). Ignore any uid which does not have filtering rule.
     *
     * @param uids an array of UIDs from which the filtering rules will be removed
     * @throws RemoteException when netd has crashed.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void removeUidInterfaceRules(final int[] uids) throws RemoteException {
        if (PRE_T) {
            mNetd.firewallRemoveUidInterfaceRules(uids);
            return;
        }
        for (final int uid: uids) {
            try {
                removeRule(uid, IIF_MATCH, "removeUidInterfaceRules");
            } catch (ServiceSpecificException e) {
                Log.e(TAG, "removeRule failed uid=" + uid + ", " + e);
            }
        }
    }

    /**
     * Update lockdown rule for uid
     *
     * @param  uid          target uid to add/remove the rule
     * @param  add          {@code true} to add the rule, {@code false} to remove the rule.
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void updateUidLockdownRule(final int uid, final boolean add) {
        throwIfPreT("updateUidLockdownRule is not available on pre-T devices");
        if (add) {
            addRule(uid, LOCKDOWN_VPN_MATCH, "updateUidLockdownRule");
        } else {
            removeRule(uid, LOCKDOWN_VPN_MATCH, "updateUidLockdownRule");
        }
    }

    /**
     * Request netd to change the current active network stats map.
     *
     * @throws ServiceSpecificException in case of failure, with an error code indicating the
     *                                  cause of the failure.
     */
    public void swapActiveStatsMap() {
        final int err = native_swapActiveStatsMap();
        maybeThrow(err, "Unable to swap active stats map");
    }

    /**
     * Assigns android.permission.INTERNET and/or android.permission.UPDATE_DEVICE_STATS to the uids
     * specified. Or remove all permissions from the uids.
     *
     * @param permissions The permission to grant, it could be either PERMISSION_INTERNET and/or
     *                    PERMISSION_UPDATE_DEVICE_STATS. If the permission is NO_PERMISSIONS, then
     *                    revoke all permissions for the uids.
     * @param uids        uid of users to grant permission
     * @throws RemoteException when netd has crashed.
     */
    public void setNetPermForUids(final int permissions, final int[] uids) throws RemoteException {
        if (PRE_T) {
            mNetd.trafficSetNetPermForUids(permissions, uids);
            return;
        }
        native_setPermissionForUids(permissions, uids);
    }

    /**
     * Dump BPF maps
     *
     * @param fd file descriptor to output
     * @throws IOException when file descriptor is invalid.
     * @throws ServiceSpecificException when the method is called on an unsupported device.
     */
    public void dump(final FileDescriptor fd, boolean verbose)
            throws IOException, ServiceSpecificException {
        if (PRE_T) {
            throw new ServiceSpecificException(
                    EOPNOTSUPP, "dumpsys connectivity trafficcontroller dump not available on pre-T"
                    + " devices, use dumpsys netd trafficcontroller instead.");
        }
        native_dump(fd, verbose);
    }

    private static native void native_init();
    @GuardedBy("sUidOwnerMap")
    private native int native_addNaughtyApp(int uid);
    private native int native_removeNaughtyApp(int uid);
    @GuardedBy("sUidOwnerMap")
    private native int native_addNiceApp(int uid);
    @GuardedBy("sUidOwnerMap")
    private native int native_removeNiceApp(int uid);
    private native int native_setChildChain(int childChain, boolean enable);
    @GuardedBy("sUidOwnerMap")
    private native int native_replaceUidChain(String name, boolean isAllowlist, int[] uids);
    @GuardedBy("sUidOwnerMap")
    private native int native_setUidRule(int childChain, int uid, int firewallRule);
    @GuardedBy("sUidOwnerMap")
    private native int native_addUidInterfaceRules(String ifName, int[] uids);
    @GuardedBy("sUidOwnerMap")
    private native int native_removeUidInterfaceRules(int[] uids);
    @GuardedBy("sUidOwnerMap")
    private native int native_updateUidLockdownRule(int uid, boolean add);
    private native int native_swapActiveStatsMap();
    private native void native_setPermissionForUids(int permissions, int[] uids);
    private native void native_dump(FileDescriptor fd, boolean verbose);
}
