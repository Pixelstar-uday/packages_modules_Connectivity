/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.net.cts;

import static android.net.IpSecAlgorithm.AUTH_CRYPT_CHACHA20_POLY1305;
import static android.net.cts.PacketUtils.CHACHA20_POLY1305;
import static android.net.cts.PacketUtils.CHACHA20_POLY1305_BLK_SIZE;
import static android.net.cts.PacketUtils.CHACHA20_POLY1305_ICV_LEN;
import static android.net.cts.PacketUtils.CHACHA20_POLY1305_IV_LEN;
import static android.net.cts.PacketUtils.CHACHA20_POLY1305_KEY_LEN;
import static android.net.cts.PacketUtils.CHACHA20_POLY1305_SALT_LEN;
import static android.net.cts.PacketUtils.ESP_HDRLEN;
import static android.net.cts.PacketUtils.IP6_HDRLEN;
import static android.net.cts.PacketUtils.getIpHeader;
import static android.net.cts.util.CtsNetUtils.TestNetworkCallback;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assume.assumeTrue;

import android.net.IpSecAlgorithm;
import android.net.IpSecManager;
import android.net.IpSecTransform;
import android.net.Network;
import android.net.TestNetworkInterface;
import android.net.cts.PacketUtils.BytePayload;
import android.net.cts.PacketUtils.EspAeadCipher;
import android.net.cts.PacketUtils.EspAuth;
import android.net.cts.PacketUtils.EspAuthNull;
import android.net.cts.PacketUtils.EspCipher;
import android.net.cts.PacketUtils.EspHeader;
import android.net.cts.PacketUtils.IpHeader;
import android.net.cts.PacketUtils.UdpHeader;
import android.platform.test.annotations.AppModeFull;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
@AppModeFull(reason = "Socket cannot bind in instant app mode")
public class IpSecAlgorithmImplTest extends IpSecBaseTest {
    private static final InetAddress LOCAL_ADDRESS =
            InetAddress.parseNumericAddress("2001:db8:1::1");
    private static final InetAddress REMOTE_ADDRESS =
            InetAddress.parseNumericAddress("2001:db8:1::2");

    private static final int REMOTE_PORT = 12345;
    private static final IpSecManager IPSEC_MANAGER =
            InstrumentationRegistry.getContext().getSystemService(IpSecManager.class);

    private static class CheckCryptoImplTest implements TestNetworkRunnable.Test {
        private final IpSecAlgorithm mIpsecEncryptAlgo;
        private final IpSecAlgorithm mIpsecAuthAlgo;
        private final IpSecAlgorithm mIpsecAeadAlgo;
        private final EspCipher mEspCipher;
        private final EspAuth mEspAuth;

        public CheckCryptoImplTest(
                IpSecAlgorithm ipsecEncryptAlgo,
                IpSecAlgorithm ipsecAuthAlgo,
                IpSecAlgorithm ipsecAeadAlgo,
                EspCipher espCipher,
                EspAuth espAuth) {
            mIpsecEncryptAlgo = ipsecEncryptAlgo;
            mIpsecAuthAlgo = ipsecAuthAlgo;
            mIpsecAeadAlgo = ipsecAeadAlgo;
            mEspCipher = espCipher;
            mEspAuth = espAuth;
        }

        private static byte[] buildTransportModeEspPayload(
                int srcPort, int dstPort, int spi, EspCipher espCipher, EspAuth espAuth)
                throws Exception {
            final UdpHeader udpPayload =
                    new UdpHeader(srcPort, dstPort, new BytePayload(TEST_DATA));
            final IpHeader preEspIpHeader =
                    getIpHeader(
                            udpPayload.getProtocolId(), LOCAL_ADDRESS, REMOTE_ADDRESS, udpPayload);

            final PacketUtils.EspHeader espPayload =
                    new EspHeader(
                            udpPayload.getProtocolId(),
                            spi,
                            1 /* sequence number */,
                            udpPayload.getPacketBytes(preEspIpHeader),
                            espCipher,
                            espAuth);
            return espPayload.getPacketBytes(preEspIpHeader);
        }

        @Override
        public void runTest(TestNetworkInterface testIface, TestNetworkCallback tunNetworkCallback)
                throws Exception {
            final TunUtils tunUtils = new TunUtils(testIface.getFileDescriptor());
            tunNetworkCallback.waitForAvailable();
            final Network testNetwork = tunNetworkCallback.currentNetwork;

            final IpSecTransform.Builder transformBuilder =
                    new IpSecTransform.Builder(InstrumentationRegistry.getContext());
            if (mIpsecAeadAlgo != null) {
                transformBuilder.setAuthenticatedEncryption(mIpsecAeadAlgo);
            } else {
                if (mIpsecEncryptAlgo != null) {
                    transformBuilder.setEncryption(mIpsecEncryptAlgo);
                }
                if (mIpsecAuthAlgo != null) {
                    transformBuilder.setAuthentication(mIpsecAuthAlgo);
                }
            }

            try (final IpSecManager.SecurityParameterIndex outSpi =
                            IPSEC_MANAGER.allocateSecurityParameterIndex(REMOTE_ADDRESS);
                    final IpSecManager.SecurityParameterIndex inSpi =
                            IPSEC_MANAGER.allocateSecurityParameterIndex(LOCAL_ADDRESS);
                    final IpSecTransform outTransform =
                            transformBuilder.buildTransportModeTransform(LOCAL_ADDRESS, outSpi);
                    final IpSecTransform inTransform =
                            transformBuilder.buildTransportModeTransform(REMOTE_ADDRESS, inSpi);
                    // Bind localSocket to a random available port.
                    final DatagramSocket localSocket = new DatagramSocket(0)) {
                IPSEC_MANAGER.applyTransportModeTransform(
                        localSocket, IpSecManager.DIRECTION_IN, inTransform);
                IPSEC_MANAGER.applyTransportModeTransform(
                        localSocket, IpSecManager.DIRECTION_OUT, outTransform);

                // Send ESP packet
                final DatagramPacket outPacket =
                        new DatagramPacket(
                                TEST_DATA, 0, TEST_DATA.length, REMOTE_ADDRESS, REMOTE_PORT);
                testNetwork.bindSocket(localSocket);
                localSocket.send(outPacket);
                final byte[] outEspPacket =
                        tunUtils.awaitEspPacket(outSpi.getSpi(), false /* useEncap */);

                // Remove transform for good hygiene
                IPSEC_MANAGER.removeTransportModeTransforms(localSocket);

                // Get the kernel-generated ESP payload
                final byte[] outEspPayload = new byte[outEspPacket.length - IP6_HDRLEN];
                System.arraycopy(outEspPacket, IP6_HDRLEN, outEspPayload, 0, outEspPayload.length);

                // Get the IV of the kernel-generated ESP payload
                final byte[] iv =
                        Arrays.copyOfRange(
                                outEspPayload, ESP_HDRLEN, ESP_HDRLEN + mEspCipher.ivLen);

                // Build ESP payload using the kernel-generated IV and the user space crypto
                // implementations
                mEspCipher.updateIv(iv);
                final byte[] expectedEspPayload =
                        buildTransportModeEspPayload(
                                localSocket.getLocalPort(),
                                REMOTE_PORT,
                                outSpi.getSpi(),
                                mEspCipher,
                                mEspAuth);

                // Compare user-space-generated and kernel-generated ESP payload
                assertArrayEquals(expectedEspPayload, outEspPayload);
            }
        }

        @Override
        public void cleanupTest() {
            // Do nothing
        }

        @Override
        public InetAddress[] getTestNetworkAddresses() {
            return new InetAddress[] {LOCAL_ADDRESS};
        }
    }

    @Test
    public void testChaCha20Poly1305() throws Exception {
        assumeTrue(hasIpSecAlgorithm(AUTH_CRYPT_CHACHA20_POLY1305));

        final byte[] cryptKey = getKeyBytes(CHACHA20_POLY1305_KEY_LEN);
        final IpSecAlgorithm ipsecAeadAlgo =
                new IpSecAlgorithm(
                        IpSecAlgorithm.AUTH_CRYPT_CHACHA20_POLY1305,
                        cryptKey,
                        CHACHA20_POLY1305_ICV_LEN * 8);
        final EspAeadCipher espAead =
                new EspAeadCipher(
                        CHACHA20_POLY1305,
                        CHACHA20_POLY1305_BLK_SIZE,
                        cryptKey,
                        CHACHA20_POLY1305_IV_LEN,
                        CHACHA20_POLY1305_ICV_LEN,
                        CHACHA20_POLY1305_SALT_LEN);

        runWithShellPermissionIdentity(
                new TestNetworkRunnable(
                        new CheckCryptoImplTest(
                                null /* ipsecEncryptAlgo */,
                                null /* ipsecAuthAlgo */,
                                ipsecAeadAlgo,
                                espAead,
                                EspAuthNull.getInstance())));
    }
}
