package com.termux.sessionsync;

import android.content.Context;

import com.jcraft.jsch.HostKeyRepository;
import com.termux.sshconnectioncore.ResolvedSshEndpoint;
import com.termux.sshconnectioncore.SshHostKeyFingerprint;
import com.termux.sshconnectioncore.SshPendingTrustRecord;
import com.termux.sshconnectioncore.SshTrustSource;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class SshHostTrustStoreTest {

    private static final String AUTHORITY_A = "ssh://bound-a.invalid:22";
    private static final String AUTHORITY_B = "ssh://bound-b.invalid:2202";

    private SshHostTrustStore store;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        store = SshHostTrustStore.getInstance();
        store.initialize(context);
        clearFixtureState();
    }

    @After
    public void tearDown() {
        clearFixtureState();
    }

    @Test
    public void repositoriesRemainBoundToTheirOwnEndpoint() {
        ResolvedSshEndpoint endpointA = endpoint(AUTHORITY_A, "bound-a.invalid", 22);
        ResolvedSshEndpoint endpointB = endpoint(AUTHORITY_B, "bound-b.invalid", 2202);
        HostKeyRepository repositoryA = store.bindEndpoint(endpointA);
        HostKeyRepository repositoryB = store.bindEndpoint(endpointB);
        byte[] keyA = ed25519PublicKey((byte) 0x11);
        byte[] keyB = ed25519PublicKey((byte) 0x55);

        // JSch supplies the transport host to both repositories. The immutable binding, not this
        // callback argument, must select the trust authority.
        Assert.assertEquals(HostKeyRepository.NOT_INCLUDED,
            repositoryA.check("same-transport.invalid", keyA));
        Assert.assertEquals(HostKeyRepository.NOT_INCLUDED,
            repositoryB.check("same-transport.invalid", keyB));

        SshPendingTrustRecord pendingA = store.findPendingByAuthority(AUTHORITY_A);
        SshPendingTrustRecord pendingB = store.findPendingByAuthority(AUTHORITY_B);
        Assert.assertNotNull(pendingA);
        Assert.assertNotNull(pendingB);
        Assert.assertEquals(SshHostKeyFingerprint.fromPublicKeyBlob(keyA),
            pendingA.observedFingerprintSha256);
        Assert.assertEquals(SshHostKeyFingerprint.fromPublicKeyBlob(keyB),
            pendingB.observedFingerprintSha256);

        Assert.assertTrue(store.approvePendingAuthority(AUTHORITY_A, SshTrustSource.USER_APPROVED));
        Assert.assertTrue(store.approvePendingAuthority(AUTHORITY_B, SshTrustSource.USER_APPROVED));
        Assert.assertEquals(1, repositoryA.getHostKey().length);
        Assert.assertEquals(1, repositoryB.getHostKey().length);
        Assert.assertEquals(android.util.Base64.encodeToString(keyA, android.util.Base64.NO_WRAP),
            repositoryA.getHostKey()[0].getKey());
        Assert.assertEquals(android.util.Base64.encodeToString(keyB, android.util.Base64.NO_WRAP),
            repositoryB.getHostKey()[0].getKey());
    }

    private void clearFixtureState() {
        if (store == null) return;
        store.dismissPendingAuthority(AUTHORITY_A);
        store.dismissPendingAuthority(AUTHORITY_B);
        store.clearAuthority(AUTHORITY_A);
        store.clearAuthority(AUTHORITY_B);
    }

    private static ResolvedSshEndpoint endpoint(String authority, String host, int port) {
        return new ResolvedSshEndpoint.Builder()
            .setAuthorityKey(authority)
            .setHost(host)
            .setHostIdentity(host)
            .setPort(port)
            .setUser("test")
            .setHostKeyVerificationMode(ResolvedSshEndpoint.HostKeyVerificationMode.YES)
            .build();
    }

    private static byte[] ed25519PublicKey(byte seed) {
        byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = ByteBuffer.allocate(4 + algorithm.length + 4 + 32);
        buffer.putInt(algorithm.length);
        buffer.put(algorithm);
        buffer.putInt(32);
        for (int i = 0; i < 32; i++) buffer.put((byte) (seed + i));
        return buffer.array();
    }
}
