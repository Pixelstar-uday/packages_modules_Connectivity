/*
 * Copyright (C) 2021 The Android Open Source Project
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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class RestrictedModeTest extends AbstractRestrictBackgroundNetworkTestCase {
    @Before
    public void setUp() throws Exception {
        super.setUp();
    }

    @After
    public void tearDown() throws Exception {
        setRestrictedMode(false);
        super.tearDown();
    }

    private void setRestrictedMode(boolean enabled) throws Exception {
        executeSilentShellCommand(
                "settings put global restricted_networking_mode " + (enabled ? 1 : 0));
        assertRestrictedModeState(enabled);
    }

    private void assertRestrictedModeState(boolean enabled) throws Exception {
        assertDelayedShellCommand("cmd netpolicy get restricted-mode",
                "Restricted mode status: " + (enabled ? "enabled" : "disabled"));
    }

    @Test
    public void testNetworkAccess() throws Exception {
        setRestrictedMode(false);

        // go to foreground state and enable restricted mode
        launchComponentAndAssertNetworkAccess(TYPE_COMPONENT_ACTIVTIY);
        setRestrictedMode(true);
        assertForegroundNetworkAccess(false);

        // go to background state
        finishActivity();
        assertBackgroundNetworkAccess(false);

        // disable restricted mode and assert network access in foreground and background states
        setRestrictedMode(false);
        launchComponentAndAssertNetworkAccess(TYPE_COMPONENT_ACTIVTIY);
        assertForegroundNetworkAccess(true);

        // go to background state
        finishActivity();
        assertBackgroundNetworkAccess(true);
    }
}
