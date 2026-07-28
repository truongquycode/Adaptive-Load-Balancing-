local _M = {}

local function calculate_entropy_weights(matrix, rows)
  local cols = 3 -- Latency, Queue, CPU
  local sum_diversity = 0.0
  local diversity = {0, 0, 0}
  local k = 1.0 / math.log(rows)
  local eps = 1e-9
  
  for j = 1, cols do
    local col_sum = 0.0
    for i = 1, rows do
      col_sum = col_sum + matrix[i][j]
    end
    
    local entropy_sum = 0.0
    for i = 1, rows do
      local p = matrix[i][j] / math.max(col_sum, eps)
      if p > eps then
        entropy_sum = entropy_sum + (p * math.log(p))
      end
    end
    
    local entropy = -k * entropy_sum
    diversity[j] = 1.0 - entropy
    sum_diversity = sum_diversity + diversity[j]
  end
  
  if sum_diversity < eps then
    return {1.0/3.0, 1.0/3.0, 1.0/3.0}
  end
  
  return {
    diversity[1] / sum_diversity,
    diversity[2] / sum_diversity,
    diversity[3] / sum_diversity
  }
end

local function clamp01(v)
  if v < 0.0 then return 0.0 end
  if v > 1.0 then return 1.0 end
  return v
end

function _M.calculate_dynamic_weights(scores_list, prior_alpha, prior_beta, prior_gamma, eps)
  local n = #scores_list
  if n < 2 then
    return prior_alpha, prior_beta, prior_gamma
  end
  
  -- Matrix: [row][col]
  -- Col 1: latency, Col 2: queue, Col 3: cpu
  local matrix = {}
  for i = 1, n do
    local s = scores_list[i]
    matrix[i] = {
      math.max(s.lat_score, eps),
      math.max(s.queue_score, eps),
      math.max(s.cpu_score, eps)
    }
  end
  
  local ewm = calculate_entropy_weights(matrix, n)
  
  -- Simple average blending (blend_factor = 0.5 in SCG default, or adaptive based on RPS)
  -- For strictness, SCG uses adaptive blending. We will approximate blend = 0.5.
  local blend = 0.5
  
  local target_alpha = blend * prior_alpha + (1 - blend) * ewm[1]
  local target_beta = blend * prior_beta + (1 - blend) * ewm[2]
  local target_gamma = blend * prior_gamma + (1 - blend) * ewm[3]
  
  local sum = target_alpha + target_beta + target_gamma
  if sum > 0 then
    return target_alpha / sum, target_beta / sum, target_gamma / sum
  else
    return prior_alpha, prior_beta, prior_gamma
  end
end

function _M.normalize(val, min_val, max_val, eps)
  if (max_val - min_val) < eps then
    return 1.0
  end
  local n = (max_val - val) / (max_val - min_val)
  if n < 0.0 then return 0.0 end
  if n > 1.0 then return 1.0 end
  return n
end

return _M
