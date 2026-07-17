package com.influora.common;

public record PageMeta(int page, int limit, long total, boolean hasMore) {}
