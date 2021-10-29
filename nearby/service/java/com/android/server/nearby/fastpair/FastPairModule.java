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

package com.android.server.nearby.fastpair;

import android.content.Context;

import com.android.server.nearby.common.locator.Locator;
import com.android.server.nearby.common.locator.Module;
import com.android.server.nearby.fastpair.cache.FastPairCacheManager;

/**
 * Module that associates all of the fast pair related singleton class
 */
public class FastPairModule extends Module {
    /**
     * Initiate the class that needs to be singleton.
     */
    @Override
    public void configure(Context context, Class<?> type, Locator locator) {
        if (type.equals(FastPairCacheManager.class)) {
            locator.bind(FastPairCacheManager.class, new FastPairCacheManager(context));
        }

    }

    /**
     * Clean up the singleton classes.
     */
    @Override
    public void destroy(Context context, Class<?> type, Object instance) {
        super.destroy(context, type, instance);
    }
}
