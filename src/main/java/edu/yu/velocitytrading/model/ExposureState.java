package edu.yu.velocitytrading.model;

public record ExposureState(int bidUsage, int askUsage, int totalCapacity, int activeReservations) {}