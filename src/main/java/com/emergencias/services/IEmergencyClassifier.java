package com.emergencias.services;

public interface IEmergencyClassifier {
    String classify(String text);
    boolean isAvailable();
}
