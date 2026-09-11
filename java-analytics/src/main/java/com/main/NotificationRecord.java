package com.main;

import java.time.Instant;

public record NotificationRecord(
        String alertKey,
        String eventType,
        String deliveryStatus,
        Integer httpStatus,
        String errorMessage,
        int attemptNumber,
        Instant attemptedAt) {
}
