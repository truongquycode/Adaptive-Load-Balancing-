package com.truongquycode.apigatewayalb.model;

import lombok.Data;

@Data
public class PidConfig {
    private double kp = 1.5;    // Tăng mạnh: error ∈ [-1,1] nên cần kp lớn
    private double ki = 0.15;    // Tích lũy có trọng lượng hơn
    private double kd = 0.05;   // Derivative nhỏ để tránh oscillation
    private double tauD = 3.0;
    private double minI = -1.0; // Thu hẹp anti-windup vì error đã nhỏ
    private double maxI = 5.0;
    private double lambda = 1.5; // Penalty weight tăng lên
    private double kappa = 1.5;  // Tanh scaling: penalty sẽ đạt 0.5 khi u ≈ 0.22
}