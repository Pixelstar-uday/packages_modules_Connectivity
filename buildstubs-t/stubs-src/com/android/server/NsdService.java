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

package com.android.server;

import android.content.Context;
import android.os.Binder;

/**
 * Fake NsdService class for sc-mainline-prod,
 * to allow building the T service-connectivity before sources
 * are moved to the branch
 */
public final class NsdService extends Binder {
    /** Create instance */
    public static NsdService create(Context ctx) throws InterruptedException {
        throw new RuntimeException("This is a stub class");
    }
}
