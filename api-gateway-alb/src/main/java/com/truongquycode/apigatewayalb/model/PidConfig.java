package com.truongquycode.apigatewayalb.model;

import lombok.Data;

@Data
public class PidConfig {
	private double kp = 0.8;
	private double ki = 0.10;
	private double kd = 0.05;
	private double tauD = 3.0;
	private double minI = -1.0;
	private double maxI = 3.0;
	private double lambda = 0.8;
	private double kappa = 1.0;
}