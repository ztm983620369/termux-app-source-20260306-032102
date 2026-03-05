package com.termux.sessionsync;

import androidx.annotation.NonNull;

interface SessionStore {

    void save(@NonNull SessionSnapshot snapshot);

    @NonNull
    SessionSnapshot load();
}

