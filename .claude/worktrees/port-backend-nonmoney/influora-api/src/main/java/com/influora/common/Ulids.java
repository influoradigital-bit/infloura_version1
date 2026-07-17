package com.influora.common;

import com.github.f4b6a3.ulid.UlidCreator;

public final class Ulids {

    private Ulids() {}

    public static String newUlid() {
        return UlidCreator.getUlid().toString();
    }
}
