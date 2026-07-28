local _M = {}

function _M.new(kp, ki, kd, min_i, max_i, error_deadband, tau_d, lambda, kappa)
  return {
    kp = kp,
    ki = ki,
    kd = kd,
    min_i = min_i,
    max_i = max_i,
    error_deadband = error_deadband,
    tau_d = tau_d,
    lambda = lambda,
    kappa = kappa,
    
    integral = 0.0,
    last_error = 0.0,
    last_derivative = 0.0
  }
end

function _M.update(pid_state, setpoint, measured, dt_ms)
  local error_val = setpoint - measured
  
  -- Deadband
  if math.abs(error_val) < pid_state.error_deadband then
    error_val = 0.0
  end
  
  local dt_sec = dt_ms / 1000.0
  if dt_sec <= 0 then dt_sec = 0.2 end
  
  -- Proportional
  local p_out = pid_state.kp * error_val
  
  -- Integral with Anti-windup
  pid_state.integral = pid_state.integral + (error_val * dt_sec)
  if pid_state.integral > pid_state.max_i then
    pid_state.integral = pid_state.max_i
  elseif pid_state.integral < pid_state.min_i then
    pid_state.integral = pid_state.min_i
  end
  local i_out = pid_state.ki * pid_state.integral
  
  -- Derivative with low-pass filter
  local derivative = (error_val - pid_state.last_error) / dt_sec
  local alpha_d = 1.0 - math.exp(-dt_sec / pid_state.tau_d)
  local filtered_d = pid_state.last_derivative + alpha_d * (derivative - pid_state.last_derivative)
  local d_out = pid_state.kd * filtered_d
  
  -- Save state
  pid_state.last_error = error_val
  pid_state.last_derivative = filtered_d
  
  -- Non-linear scaling (lambda/kappa) for penalty shaping
  local total_out = p_out + i_out + d_out
  local penalty = pid_state.lambda * math.pow(math.abs(total_out), pid_state.kappa)
  if total_out < 0 then
    penalty = -penalty
  end
  
  return penalty
end

return _M
