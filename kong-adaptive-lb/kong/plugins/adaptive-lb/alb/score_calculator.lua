local _M = {}

-- Equivalent to ScoreCalculator.java
function _M.calculate_node_score(lat_score, queue_score, cpu_score, alpha, beta, gamma, pid_penalty)
  -- Base Score = AHP/EWM blend
  local base = (lat_score * alpha) + (queue_score * beta) + (cpu_score * gamma)
  
  -- Apply PID penalty
  local final_score = base + pid_penalty
  
  -- Clamp > 0
  if final_score < 0.1 then
    final_score = 0.1
  end
  
  return final_score
end

-- Normalize metrics into [0, 1] scale relative to the cluster
function _M.normalize(val, min_val, max_val, eps)
  local range = max_val - min_val
  if range < eps then
    return 1.0
  end
  -- Lower is better for latency/queue/cpu, so invert the scale
  -- 0.0 -> max_val (worst)
  -- 1.0 -> min_val (best)
  local norm = 1.0 - ((val - min_val) / range)
  if norm < 0.0 then return 0.0 end
  if norm > 1.0 then return 1.0 end
  return norm
end

return _M
