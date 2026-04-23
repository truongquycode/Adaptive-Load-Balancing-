package com.truongquycode.apigatewayalb.model;

public enum CircuitState {
    CLOSED,     // Hoạt động bình thường
    OPEN,       // Đang phạt nặng — không route traffic đến instance này
    HALF_OPEN   // Đang thử phục hồi — cho qua một lượng nhỏ traffic để kiểm tra
}
