package com.truongquycode.apigatewayalb.model;

import lombok.Data;

@Data
public class PidState {
    private double lastError = 0.0;
    private double integral = 0.0;
    private double lastFilteredD = 0.0;
    private double lastOutput = 0.0;
    private long lastTimestamp = 0;
}