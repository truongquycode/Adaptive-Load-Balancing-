local _M = {}

-- Chuẩn hóa Min-Max cho Latency
function _M.normalize_latency(latency, sys_p5, inv_range)
  local n = (latency - sys_p5) * inv_range
  if n < 0.0 then return 0.0 end
  if n > 1.0 then return 1.0 end
  return n
end

-- Chuẩn hóa Log-scale cho Queue
function _M.normalize_queue(queue, p99)
  local max_q = math.max(1.0, p99)
  local val = math.max(0.0, queue)
  local n = math.log(1.0 + val) / math.log(1.0 + max_q)
  if n < 0.0 then return 0.0 end
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
