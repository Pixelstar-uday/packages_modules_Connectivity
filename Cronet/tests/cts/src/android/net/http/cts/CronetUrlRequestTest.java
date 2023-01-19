/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.http.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.http.HttpEngine;
import android.net.http.UrlRequest;
import android.net.http.UrlRequest.Status;
import android.net.http.UrlResponseInfo;
import android.net.http.cts.util.CronetCtsTestServer;
import android.net.http.cts.util.TestStatusListener;
import android.net.http.cts.util.TestUrlRequestCallback;
import android.net.http.cts.util.TestUrlRequestCallback.ResponseStep;

import androidx.annotation.NonNull;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CronetUrlRequestTest {
    private static final String TAG = CronetUrlRequestTest.class.getSimpleName();

    @NonNull private HttpEngine mHttpEngine;
    @NonNull private TestUrlRequestCallback mCallback;
    @NonNull private ConnectivityManager mCm;
    @NonNull private CronetCtsTestServer mTestServer;

    @Before
    public void setUp() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mCm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        HttpEngine.Builder builder = new HttpEngine.Builder(context);
        builder.setEnableHttpCache(HttpEngine.Builder.HTTP_CACHE_IN_MEMORY, 100 * 1024)
                .setEnableHttp2(true)
                // .setEnableBrotli(true)
                .setEnableQuic(true);
        mHttpEngine = builder.build();
        mCallback = new TestUrlRequestCallback();
        mTestServer = new CronetCtsTestServer(context);
    }

    @After
    public void tearDown() throws Exception {
        mHttpEngine.shutdown();
        mTestServer.shutdown();
    }

    private static void assertGreaterThan(String msg, int first, int second) {
        assertTrue(msg + " Excepted " + first + " to be greater than " + second, first > second);
    }

    private void assertHasTestableNetworks() {
        assertNotNull("This test requires a working Internet connection", mCm.getActiveNetwork());
    }

    private UrlRequest buildUrlRequest(String url) {
        return mHttpEngine.newUrlRequestBuilder(url, mCallback, mCallback.getExecutor()).build();
    }

    @Test
    public void testUrlRequestGet_CompletesSuccessfully() throws Exception {
        assertHasTestableNetworks();
        String url = mTestServer.getSuccessUrl();
        UrlRequest request = buildUrlRequest(url);
        request.start();

        mCallback.expectCallback(ResponseStep.ON_SUCCEEDED);

        UrlResponseInfo info = mCallback.mResponseInfo;
        assertEquals(
                "Unexpected http status code from " + url + ".", 200, info.getHttpStatusCode());
        assertGreaterThan(
                "Received byte from " + url + " is 0.", (int) info.getReceivedByteCount(), 0);
    }

    @Test
    public void testUrlRequestStatus_InvalidBeforeRequestStarts() throws Exception {
        UrlRequest request = buildUrlRequest(mTestServer.getSuccessUrl());
        // Calling before request is started should give Status.INVALID,
        // since the native adapter is not created.
        TestStatusListener statusListener = new TestStatusListener();
        request.getStatus(statusListener);
        statusListener.expectStatus(Status.INVALID);
    }
}
