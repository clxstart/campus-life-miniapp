package com.campuslife.dto;

import java.util.List;

public record AuditResult(boolean pass, boolean reviewing, String reason, List<String> words) {
    public static AuditResult passed() { return new AuditResult(true, false, null, List.of()); }
}
