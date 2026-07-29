local _M = {}

function _M.smooth(current_ema, new_val, dt, tau_min, tau_max, k, fallback_p50)
  if current_ema == nil or current_ema == 0 then return fallback_p50 end
  
  local deviation = math.abs(new_val - current_ema) / math.max(current_ema, 1.0)
  
  local kd = k * deviation
  local adaptive_tau
  if kd >= 6.0 then
    adaptive_tau = tau_min
  else
    adaptive_tau = tau_min + (tau_max - tau_min) * math.exp(-kd)
  end
  
  local ratio = dt / adaptive_tau
  local theta
  if ratio >= 10.0 then
    theta = 1.0
  else
    theta = 1.0 - math.exp(-ratio)
  end
  
  return (theta * new_val) + ((1.0 - theta) * current_ema)
end

return _M
