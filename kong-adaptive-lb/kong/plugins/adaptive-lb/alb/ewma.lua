local _M = {}

function _M.calculate_tau(error_val, tau_min, tau_max, k)
  -- Tau adapts inversely to error. Larger error = smaller tau (faster response)
  local alpha = 1.0 - math.exp(-math.abs(error_val) * k)
  return tau_max - alpha * (tau_max - tau_min)
end

function _M.smooth(current_ema, new_val, dt, tau_min, tau_max, k, math_eps)
  if current_ema == nil or current_ema == 0 then return new_val end
  
  -- Prevent division by zero
  local error_val = new_val - current_ema
  local relative_error = error_val / (current_ema + math_eps)
  
  local tau = _M.calculate_tau(relative_error, tau_min, tau_max, k)
  
  -- Calculate alpha for EMA
  local alpha = 1.0 - math.exp(-dt / tau)
  
  return current_ema + alpha * error_val
end

return _M
