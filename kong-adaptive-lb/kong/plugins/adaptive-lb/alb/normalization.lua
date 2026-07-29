local _M = {}

-- Chuẩn hóa Min-Max cho Latency
function _M.normalize_latency(latency, sys_p5, inv_range)
  local n = (latency - sys_p5) * inv_range
  if n < 0.0 then return 0.0 end
  if n > 1.0 then return 1.0 end
  return n
end

-- Chuẩn hóa Log-scale + Linear blend cho Queue
function _M.normalize_queue(queue, p99)
  if queue <= 0.0 then return 0.0 end
  
  local q_max = math.max(p99, 40.0)
  
  local inv_log_denom = 1.0 / math.log(1.0 + q_max)
  local log_score = math.log(1.0 + queue) * inv_log_denom
  
  local linear_score = queue / (q_max * 2.0)
  if linear_score > 1.0 then linear_score = 1.0 end
  
  local n = 0.6 * log_score + 0.4 * linear_score
  if n > 1.0 then return 1.0 end
  return n
end

-- Chuẩn hóa Clamp cho CPU
function _M.normalize_cpu(cpu)
  if not cpu or cpu ~= cpu then
    return 0.5 -- Fallback for NaN or nil
  end
  if cpu < 0.0 then return 0.0 end
  if cpu > 1.0 then return 1.0 end
  return cpu
end

return _M
