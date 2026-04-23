package com.truongquycode.apigatewayalb.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class InstanceMetrics {
    private String instanceId;
    private double latency;
    private double queueLength;
    private double cpu;
}