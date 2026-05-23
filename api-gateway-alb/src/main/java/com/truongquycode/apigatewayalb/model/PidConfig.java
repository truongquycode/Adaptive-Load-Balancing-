package com.truongquycode.apigatewayalb.model;

import lombok.Data;

@Data
public class PidConfig {
    private double kp = 1.5;    // Tăng từ 1.5 -> 2.5 để phản ứng P gắt hơn
    private double ki = 0.15;    // Tích lũy có trọng lượng hơn 0.15 -> 0.2
    private double kd = 0.05;   // Derivative nhỏ để tránh oscillation
    private double tauD = 3.0;
    private double minI = -1.0; // Thu hẹp anti-windup vì error đã nhỏ
    private double maxI = 5.0; //5 -> 10
    private double lambda = 0.8; // Penalty weight tăng lên 0.8 -> 3
    private double kappa = 1.0;  // Tanh scaling: penalty sẽ đạt 0.5 khi u ≈ 0.22
}