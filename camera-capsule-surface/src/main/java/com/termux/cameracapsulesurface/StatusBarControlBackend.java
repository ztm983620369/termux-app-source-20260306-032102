package com.termux.cameracapsulesurface;

import androidx.annotation.NonNull;

interface StatusBarControlBackend {

    @NonNull
    String getName();

    boolean isSupported();

    @NonNull
    StatusBarControlResult apply(@NonNull StatusBarDisableSpec spec);

    @NonNull
    StatusBarControlResult restore();
}
