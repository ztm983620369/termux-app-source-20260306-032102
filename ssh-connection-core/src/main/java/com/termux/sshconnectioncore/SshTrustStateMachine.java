package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

public final class SshTrustStateMachine {

    public enum State {
        IDLE,
        EVALUATING,
        TRUST_ABSENT,
        TRUST_PENDING_APPROVAL,
        TRUST_MATCHED,
        TRUST_CONFLICT,
        TRUST_CLEARED
    }

    public enum EventType {
        BEGIN_EVALUATION,
        OBSERVE_HOST_KEY,
        APPROVE_PENDING,
        REPLACE_TRUST,
        CLEAR_TRUST,
        RESET
    }

    public static final class Event {
        @NonNull public final EventType type;
        @Nullable public final ResolvedSshEndpoint endpoint;
        @Nullable public final SshTrustRecord storedRecord;
        @Nullable public final String algorithm;
        @Nullable public final String fingerprintSha256;
        @Nullable public final SshTrustSource trustSource;
        @Nullable public final String detail;
        public final long atMs;

        private Event(@NonNull EventType type,
                      @Nullable ResolvedSshEndpoint endpoint,
                      @Nullable SshTrustRecord storedRecord,
                      @Nullable String algorithm,
                      @Nullable String fingerprintSha256,
                      @Nullable SshTrustSource trustSource,
                      @Nullable String detail,
                      long atMs) {
            this.type = type;
            this.endpoint = endpoint;
            this.storedRecord = storedRecord;
            this.algorithm = algorithm;
            this.fingerprintSha256 = fingerprintSha256;
            this.trustSource = trustSource;
            this.detail = detail;
            this.atMs = Math.max(0L, atMs);
        }

        @NonNull
        public static Event beginEvaluation(@NonNull ResolvedSshEndpoint endpoint,
                                            @Nullable SshTrustRecord storedRecord,
                                            long atMs) {
            return new Event(EventType.BEGIN_EVALUATION, endpoint, storedRecord,
                null, null, null, null, atMs);
        }

        @NonNull
        public static Event observeHostKey(@NonNull String algorithm,
                                           @NonNull String fingerprintSha256,
                                           long atMs) {
            return new Event(EventType.OBSERVE_HOST_KEY, null, null, algorithm,
                fingerprintSha256, null, null, atMs);
        }

        @NonNull
        public static Event approvePending(@NonNull SshTrustSource trustSource, long atMs) {
            return new Event(EventType.APPROVE_PENDING, null, null, null,
                null, trustSource, null, atMs);
        }

        @NonNull
        public static Event replaceTrust(@NonNull SshTrustSource trustSource, long atMs) {
            return new Event(EventType.REPLACE_TRUST, null, null, null,
                null, trustSource, null, atMs);
        }

        @NonNull
        public static Event clearTrust(@Nullable String detail, long atMs) {
            return new Event(EventType.CLEAR_TRUST, null, null, null,
                null, null, detail, atMs);
        }

        @NonNull
        public static Event reset(@Nullable String detail, long atMs) {
            return new Event(EventType.RESET, null, null, null,
                null, null, detail, atMs);
        }
    }

    public static final class Snapshot {
        @NonNull public final State state;
        @Nullable public final ResolvedSshEndpoint endpoint;
        @Nullable public final SshTrustRecord storedRecord;
        @Nullable public final SshTrustRecord effectiveRecord;
        @NonNull public final String observedAlgorithm;
        @NonNull public final String observedFingerprintSha256;
        @NonNull public final String detail;
        @NonNull public final SshControlAction suggestedAction;
        public final long updatedAtMs;

        Snapshot(@NonNull State state,
                 @Nullable ResolvedSshEndpoint endpoint,
                 @Nullable SshTrustRecord storedRecord,
                 @Nullable SshTrustRecord effectiveRecord,
                 @NonNull String observedAlgorithm,
                 @NonNull String observedFingerprintSha256,
                 @NonNull String detail,
                 @NonNull SshControlAction suggestedAction,
                 long updatedAtMs) {
            this.state = state;
            this.endpoint = endpoint;
            this.storedRecord = storedRecord;
            this.effectiveRecord = effectiveRecord;
            this.observedAlgorithm = safe(observedAlgorithm);
            this.observedFingerprintSha256 = safe(observedFingerprintSha256);
            this.detail = safe(detail);
            this.suggestedAction = suggestedAction == null ? SshControlAction.NONE : suggestedAction;
            this.updatedAtMs = Math.max(0L, updatedAtMs);
        }
    }

    @NonNull
    private Snapshot snapshot = new Snapshot(
        State.IDLE,
        null,
        null,
        null,
        "",
        "",
        "",
        SshControlAction.NONE,
        0L
    );

    @NonNull
    public Snapshot apply(@NonNull Event event) {
        switch (event.type) {
            case BEGIN_EVALUATION:
                snapshot = new Snapshot(
                    event.storedRecord == null ? State.TRUST_ABSENT : State.EVALUATING,
                    event.endpoint,
                    event.storedRecord,
                    event.storedRecord,
                    "",
                    "",
                    "",
                    SshControlAction.NONE,
                    event.atMs
                );
                return snapshot;
            case OBSERVE_HOST_KEY:
                return applyObserve(event);
            case APPROVE_PENDING:
                return applyApproval(event, false);
            case REPLACE_TRUST:
                return applyApproval(event, true);
            case CLEAR_TRUST:
                snapshot = new Snapshot(
                    State.TRUST_CLEARED,
                    snapshot.endpoint,
                    null,
                    null,
                    snapshot.observedAlgorithm,
                    snapshot.observedFingerprintSha256,
                    safe(event.detail),
                    SshControlAction.RETRY,
                    event.atMs
                );
                return snapshot;
            case RESET:
            default:
                snapshot = new Snapshot(
                    State.IDLE,
                    null,
                    null,
                    null,
                    "",
                    "",
                    safe(event.detail),
                    SshControlAction.NONE,
                    event.atMs
                );
                return snapshot;
        }
    }

    @NonNull
    public Snapshot snapshot() {
        return snapshot;
    }

    @NonNull
    private Snapshot applyObserve(@NonNull Event event) {
        if (snapshot.endpoint == null) {
            snapshot = new Snapshot(
                State.TRUST_ABSENT,
                null,
                null,
                null,
                safe(event.algorithm),
                safe(event.fingerprintSha256),
                "missing endpoint context",
                SshControlAction.NONE,
                event.atMs
            );
            return snapshot;
        }

        if (snapshot.storedRecord == null) {
            snapshot = new Snapshot(
                State.TRUST_PENDING_APPROVAL,
                snapshot.endpoint,
                null,
                null,
                safe(event.algorithm),
                safe(event.fingerprintSha256),
                "",
                SshControlAction.APPROVE_TRUST,
                event.atMs
            );
            return snapshot;
        }

        if (snapshot.storedRecord.matchesObserved(event.algorithm, event.fingerprintSha256)) {
            SshTrustRecord updated = snapshot.storedRecord.withLastSeenAtMs(event.atMs);
            snapshot = new Snapshot(
                State.TRUST_MATCHED,
                snapshot.endpoint,
                updated,
                updated,
                safe(event.algorithm),
                safe(event.fingerprintSha256),
                "",
                SshControlAction.NONE,
                event.atMs
            );
            return snapshot;
        }

        String detail = "stored=" + snapshot.storedRecord.fingerprintSha256
            + " incoming=" + safe(event.fingerprintSha256);
        snapshot = new Snapshot(
            State.TRUST_CONFLICT,
            snapshot.endpoint,
            snapshot.storedRecord,
            null,
            safe(event.algorithm),
            safe(event.fingerprintSha256),
            detail,
            SshControlAction.REPLACE_TRUST,
            event.atMs
        );
        return snapshot;
    }

    @NonNull
    private Snapshot applyApproval(@NonNull Event event, boolean replace) {
        if (snapshot.endpoint == null) {
            return snapshot;
        }
        if (snapshot.observedFingerprintSha256.isEmpty() || snapshot.observedAlgorithm.isEmpty()) {
            return snapshot;
        }
        if (!replace && snapshot.state != State.TRUST_PENDING_APPROVAL) {
            return snapshot;
        }
        if (replace && snapshot.state != State.TRUST_CONFLICT) {
            return snapshot;
        }

        SshTrustRecord base = snapshot.storedRecord;
        SshTrustRecord effective;
        if (base == null || replace) {
            effective = new SshTrustRecord(
                snapshot.endpoint.authorityKey,
                snapshot.endpoint.hostIdentity,
                snapshot.endpoint.port,
                snapshot.observedAlgorithm,
                snapshot.observedFingerprintSha256,
                event.trustSource == null ? SshTrustSource.USER_APPROVED : event.trustSource,
                event.atMs,
                event.atMs
            );
        } else {
            effective = base.replaceWith(
                snapshot.observedAlgorithm,
                snapshot.observedFingerprintSha256,
                event.trustSource == null ? SshTrustSource.USER_APPROVED : event.trustSource,
                event.atMs
            );
        }

        snapshot = new Snapshot(
            State.TRUST_MATCHED,
            snapshot.endpoint,
            effective,
            effective,
            snapshot.observedAlgorithm,
            snapshot.observedFingerprintSha256,
            "",
            SshControlAction.NONE,
            event.atMs
        );
        return snapshot;
    }

    @NonNull
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
