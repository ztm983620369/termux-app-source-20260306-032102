package com.termux.sshconnectioncore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class SshConnectionOrchestratorStateMachine {

    public enum Phase {
        IDLE,
        PREPARING_PROFILE,
        PROFILE_READY,
        TRUST_EVALUATING,
        TRUST_BLOCKED,
        TRUST_READY,
        ENGINE_SELECTED,
        CONNECTING,
        READY,
        RECOVERING,
        FAILED,
        CANCELLED
    }

    public enum EventType {
        BEGIN,
        PROFILE_RESOLVED,
        PROFILE_INVALID,
        TRUST_EVALUATING,
        TRUST_BLOCKED,
        TRUST_READY,
        ENGINE_SELECTED,
        CONNECTING,
        READY,
        RECOVERING,
        FAILED,
        CANCELLED,
        RESET
    }

    public static final class Event {
        @NonNull public final EventType type;
        @Nullable public final SshConnectionIntent intent;
        @Nullable public final SshConnectionEngine engine;
        @Nullable public final ResolvedSshEndpoint endpoint;
        @Nullable public final String detail;
        @Nullable public final SshConnectionFailureCategory failureCategory;
        @Nullable public final SshControlAction suggestedAction;
        public final int attempt;
        public final long atMs;

        private Event(@NonNull EventType type,
                      @Nullable SshConnectionIntent intent,
                      @Nullable SshConnectionEngine engine,
                      @Nullable ResolvedSshEndpoint endpoint,
                      @Nullable String detail,
                      @Nullable SshConnectionFailureCategory failureCategory,
                      @Nullable SshControlAction suggestedAction,
                      int attempt,
                      long atMs) {
            this.type = type;
            this.intent = intent;
            this.engine = engine;
            this.endpoint = endpoint;
            this.detail = detail;
            this.failureCategory = failureCategory;
            this.suggestedAction = suggestedAction;
            this.attempt = Math.max(0, attempt);
            this.atMs = Math.max(0L, atMs);
        }

        @NonNull
        public static Event begin(@NonNull SshConnectionIntent intent,
                                  @Nullable SshConnectionEngine engine,
                                  long atMs) {
            return new Event(EventType.BEGIN, intent, engine, null, null,
                SshConnectionFailureCategory.NONE, SshControlAction.NONE, 0, atMs);
        }

        @NonNull
        public static Event profileResolved(@NonNull ResolvedSshEndpoint endpoint, long atMs) {
            return new Event(EventType.PROFILE_RESOLVED, null, null, endpoint, null,
                SshConnectionFailureCategory.NONE, SshControlAction.NONE, 0, atMs);
        }

        @NonNull
        public static Event profileInvalid(@NonNull SshConnectionFailureCategory failureCategory,
                                           @Nullable String detail,
                                           @NonNull SshControlAction suggestedAction,
                                           long atMs) {
            return new Event(EventType.PROFILE_INVALID, null, null, null, detail,
                failureCategory, suggestedAction, 0, atMs);
        }

        @NonNull
        public static Event trustEvaluating(@NonNull ResolvedSshEndpoint endpoint, long atMs) {
            return new Event(EventType.TRUST_EVALUATING, null, null, endpoint, null,
                SshConnectionFailureCategory.NONE, SshControlAction.NONE, 0, atMs);
        }

        @NonNull
        public static Event trustBlocked(@NonNull SshConnectionFailureCategory failureCategory,
                                         @Nullable String detail,
                                         @NonNull SshControlAction suggestedAction,
                                         long atMs) {
            return new Event(EventType.TRUST_BLOCKED, null, null, null, detail,
                failureCategory, suggestedAction, 0, atMs);
        }

        @NonNull
        public static Event trustReady(long atMs) {
            return new Event(EventType.TRUST_READY, null, null, null, null,
                SshConnectionFailureCategory.NONE, SshControlAction.NONE, 0, atMs);
        }

        @NonNull
        public static Event engineSelected(@NonNull SshConnectionEngine engine,
                                           @Nullable String detail,
                                           long atMs) {
            return new Event(EventType.ENGINE_SELECTED, null, engine, null, detail,
                SshConnectionFailureCategory.NONE, SshControlAction.NONE, 0, atMs);
        }

        @NonNull
        public static Event connecting(int attempt, @Nullable String detail, long atMs) {
            return new Event(EventType.CONNECTING, null, null, null, detail,
                SshConnectionFailureCategory.NONE, SshControlAction.NONE, attempt, atMs);
        }

        @NonNull
        public static Event ready(@Nullable String detail, long atMs) {
            return new Event(EventType.READY, null, null, null, detail,
                SshConnectionFailureCategory.NONE, SshControlAction.NONE, 0, atMs);
        }

        @NonNull
        public static Event recovering(@NonNull SshConnectionFailureCategory failureCategory,
                                       @Nullable String detail,
                                       @NonNull SshControlAction suggestedAction,
                                       int attempt,
                                       long atMs) {
            return new Event(EventType.RECOVERING, null, null, null, detail,
                failureCategory, suggestedAction, attempt, atMs);
        }

        @NonNull
        public static Event failed(@NonNull SshConnectionFailureCategory failureCategory,
                                   @Nullable String detail,
                                   @NonNull SshControlAction suggestedAction,
                                   int attempt,
                                   long atMs) {
            return new Event(EventType.FAILED, null, null, null, detail,
                failureCategory, suggestedAction, attempt, atMs);
        }

        @NonNull
        public static Event cancelled(@Nullable String detail, long atMs) {
            return new Event(EventType.CANCELLED, null, null, null, detail,
                SshConnectionFailureCategory.CANCELLED, SshControlAction.NONE, 0, atMs);
        }

        @NonNull
        public static Event reset(long atMs) {
            return new Event(EventType.RESET, null, null, null, null,
                SshConnectionFailureCategory.NONE, SshControlAction.NONE, 0, atMs);
        }
    }

    public static final class Snapshot {
        @NonNull public final Phase phase;
        @Nullable public final SshConnectionIntent intent;
        @Nullable public final SshConnectionEngine requestedEngine;
        @Nullable public final SshConnectionEngine activeEngine;
        @Nullable public final ResolvedSshEndpoint endpoint;
        @NonNull public final String detail;
        @NonNull public final SshConnectionFailureCategory failureCategory;
        @NonNull public final SshControlAction suggestedAction;
        public final int attempt;
        public final long updatedAtMs;

        Snapshot(@NonNull Phase phase,
                 @Nullable SshConnectionIntent intent,
                 @Nullable SshConnectionEngine requestedEngine,
                 @Nullable SshConnectionEngine activeEngine,
                 @Nullable ResolvedSshEndpoint endpoint,
                 @NonNull String detail,
                 @NonNull SshConnectionFailureCategory failureCategory,
                 @NonNull SshControlAction suggestedAction,
                 int attempt,
                 long updatedAtMs) {
            this.phase = phase;
            this.intent = intent;
            this.requestedEngine = requestedEngine;
            this.activeEngine = activeEngine;
            this.endpoint = endpoint;
            this.detail = safe(detail);
            this.failureCategory = failureCategory == null ? SshConnectionFailureCategory.NONE : failureCategory;
            this.suggestedAction = suggestedAction == null ? SshControlAction.NONE : suggestedAction;
            this.attempt = Math.max(0, attempt);
            this.updatedAtMs = Math.max(0L, updatedAtMs);
        }
    }

    @NonNull
    private Snapshot snapshot = new Snapshot(
        Phase.IDLE,
        null,
        null,
        null,
        null,
        "",
        SshConnectionFailureCategory.NONE,
        SshControlAction.NONE,
        0,
        0L
    );

    @NonNull
    public Snapshot apply(@NonNull Event event) {
        switch (event.type) {
            case BEGIN:
                snapshot = new Snapshot(
                    Phase.PREPARING_PROFILE,
                    event.intent,
                    event.engine,
                    null,
                    null,
                    "",
                    SshConnectionFailureCategory.NONE,
                    SshControlAction.NONE,
                    0,
                    event.atMs
                );
                return snapshot;
            case PROFILE_RESOLVED:
                snapshot = new Snapshot(
                    Phase.PROFILE_READY,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    null,
                    event.endpoint,
                    "",
                    SshConnectionFailureCategory.NONE,
                    SshControlAction.NONE,
                    0,
                    event.atMs
                );
                return snapshot;
            case PROFILE_INVALID:
                snapshot = new Snapshot(
                    Phase.FAILED,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    null,
                    snapshot.endpoint,
                    safe(event.detail),
                    event.failureCategory,
                    event.suggestedAction,
                    0,
                    event.atMs
                );
                return snapshot;
            case TRUST_EVALUATING:
                snapshot = new Snapshot(
                    Phase.TRUST_EVALUATING,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    null,
                    event.endpoint == null ? snapshot.endpoint : event.endpoint,
                    "",
                    SshConnectionFailureCategory.NONE,
                    SshControlAction.NONE,
                    0,
                    event.atMs
                );
                return snapshot;
            case TRUST_BLOCKED:
                snapshot = new Snapshot(
                    Phase.TRUST_BLOCKED,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    null,
                    snapshot.endpoint,
                    safe(event.detail),
                    event.failureCategory,
                    event.suggestedAction,
                    0,
                    event.atMs
                );
                return snapshot;
            case TRUST_READY:
                snapshot = new Snapshot(
                    Phase.TRUST_READY,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    null,
                    snapshot.endpoint,
                    "",
                    SshConnectionFailureCategory.NONE,
                    SshControlAction.NONE,
                    0,
                    event.atMs
                );
                return snapshot;
            case ENGINE_SELECTED:
                snapshot = new Snapshot(
                    Phase.ENGINE_SELECTED,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    event.engine,
                    snapshot.endpoint,
                    safe(event.detail),
                    SshConnectionFailureCategory.NONE,
                    SshControlAction.NONE,
                    0,
                    event.atMs
                );
                return snapshot;
            case CONNECTING:
                snapshot = new Snapshot(
                    Phase.CONNECTING,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    snapshot.activeEngine == null ? snapshot.requestedEngine : snapshot.activeEngine,
                    snapshot.endpoint,
                    safe(event.detail),
                    SshConnectionFailureCategory.NONE,
                    SshControlAction.NONE,
                    event.attempt,
                    event.atMs
                );
                return snapshot;
            case READY:
                snapshot = new Snapshot(
                    Phase.READY,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    snapshot.activeEngine == null ? snapshot.requestedEngine : snapshot.activeEngine,
                    snapshot.endpoint,
                    safe(event.detail),
                    SshConnectionFailureCategory.NONE,
                    SshControlAction.NONE,
                    snapshot.attempt,
                    event.atMs
                );
                return snapshot;
            case RECOVERING:
                snapshot = new Snapshot(
                    Phase.RECOVERING,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    snapshot.activeEngine == null ? snapshot.requestedEngine : snapshot.activeEngine,
                    snapshot.endpoint,
                    safe(event.detail),
                    event.failureCategory,
                    event.suggestedAction,
                    event.attempt,
                    event.atMs
                );
                return snapshot;
            case FAILED:
                snapshot = new Snapshot(
                    Phase.FAILED,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    snapshot.activeEngine == null ? snapshot.requestedEngine : snapshot.activeEngine,
                    snapshot.endpoint,
                    safe(event.detail),
                    event.failureCategory,
                    event.suggestedAction,
                    event.attempt,
                    event.atMs
                );
                return snapshot;
            case CANCELLED:
                snapshot = new Snapshot(
                    Phase.CANCELLED,
                    snapshot.intent,
                    snapshot.requestedEngine,
                    snapshot.activeEngine,
                    snapshot.endpoint,
                    safe(event.detail),
                    SshConnectionFailureCategory.CANCELLED,
                    SshControlAction.NONE,
                    snapshot.attempt,
                    event.atMs
                );
                return snapshot;
            case RESET:
            default:
                snapshot = new Snapshot(
                    Phase.IDLE,
                    null,
                    null,
                    null,
                    null,
                    "",
                    SshConnectionFailureCategory.NONE,
                    SshControlAction.NONE,
                    0,
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
    private static String safe(@Nullable String value) {
        return value == null ? "" : value.trim();
    }
}
