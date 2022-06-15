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

package com.android.server.nearby.common.locator;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.core.app.ApplicationProvider;

import com.android.server.nearby.common.eventloop.EventLoop;
import com.android.server.nearby.fastpair.FastPairAdvHandler;
import com.android.server.nearby.fastpair.FastPairModule;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;
import com.android.server.nearby.fastpair.footprint.FootprintsDeviceManager;
import com.android.server.nearby.fastpair.halfsheet.FastPairHalfSheetManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.MockitoAnnotations;

import java.time.Clock;

public class LocatorTest {
    private Locator mLocator;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLocator = src.com.android.server.nearby.fastpair.testing.MockingLocator.withMocksOnly(
                ApplicationProvider.getApplicationContext());
        mLocator.bind(new FastPairModule());
    }

    @Test
    public void genericConstructor() {
        assertThat(mLocator.get(FastPairCacheManager.class)).isNotNull();
        assertThat(mLocator.get(FootprintsDeviceManager.class)).isNotNull();
        assertThat(mLocator.get(EventLoop.class)).isNotNull();
        assertThat(mLocator.get(FastPairHalfSheetManager.class)).isNotNull();
        assertThat(mLocator.get(FastPairAdvHandler.class)).isNotNull();
        assertThat(mLocator.get(Clock.class)).isNotNull();
    }

    @Test
    public void genericDestroy() {
        mLocator.destroy();
    }

    @Test
    public void getOptional() {
        assertThat(mLocator.getOptional(FastPairModule.class)).isNotNull();
        mLocator.removeBindingForTest(FastPairModule.class);
        assertThat(mLocator.getOptional(FastPairModule.class)).isNull();
    }

    @Test
    public void getParent() {
        assertThat(mLocator.getParent()).isNotNull();
    }

    @Test
    public void getUnboundErrorMessage() {
        assertThat(mLocator.getUnboundErrorMessage(FastPairModule.class))
                .isEqualTo(
                        "Unbound type: com.android.server.nearby.fastpair.FastPairModule\n"
                        + "Searched locators:\n" + "android.app.Application ->\n"
                                + "android.app.Application ->\n" + "android.app.Application");
    }
}
