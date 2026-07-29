local _M = {}

local LN_0_97 = math.log(0.97)

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
    last_raw_lat = 0.0,
    last_filtered_d = 0.0,
    last_output = 0.0,
    last_timestamp = ngx.now() * 1000 - 200.0
  }
end

function _M.update(state, setpoint, raw_lat, interval_ms)
  local now = ngx.now() * 1000
  local dt_sec = (now - state.last_timestamp) / 1000.0
  dt_sec = math.max(0.001, math.min(5.0, dt_sec))

  local error_val = raw_lat - setpoint
  
  if math.abs(error_val) <= state.error_deadband then
    error_val = 0.0
  elseif error_val > 0.0 then
    error_val = error_val - state.error_deadband
  else
    error_val = error_val + state.error_deadband
  end
  
  local p = state.kp * error_val
  
  local is_saturated = math.abs(state.last_output) >= 2.0
  local same_sign = (error_val * state.last_output) > 0.0
  
  local integral = state.integral
  if not (is_saturated and same_sign) then
    local new_i = state.integral + (error_val * dt_sec)
    if math.abs(error_val) < 0.1 then
      new_i = new_i * math.exp(LN_0_97 * dt_sec)
    end
    integral = math.max(state.min_i, math.min(state.max_i, new_i))
    state.integral = integral
  end
  
  local i = state.ki * integral
  
  local raw_d = (raw_lat - state.last_raw_lat) / dt_sec
  local exp_term = math.exp(-dt_sec / state.tau_d)
  local filtered_d = ((1.0 - exp_term) * raw_d) + (exp_term * state.last_filtered_d)
  local d = state.kd * filtered_d
  
  local u = p + i + d
  
  state.last_raw_lat = raw_lat
  state.last_filtered_d = filtered_d
  state.last_output = u
  state.last_timestamp = now
  
  local penalty = state.lambda * math.tanh(state.kappa * math.max(0.0, u))
  return penalty
end

return _M
