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

package android.nearby.integration.privileged

import android.content.Context
import android.nearby.NearbyManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NearbyManagerTest {

    /** Verify privileged app can get Nearby service. */
    @Test
    fun testContextGetNearbySystemService_fromPrivilegedApp_returnsNoneNull() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val nearbyManager = appContext.getSystemService(Context.NEARBY_SERVICE) as NearbyManager

        assertThat(nearbyManager).isNotNull()
    }
}
