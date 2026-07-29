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

function _M.calculate_entropy_weights_from_scores(scores_list, n)
  local eps = 1e-9
  local matrix = {}
  for i = 1, n do
    local s = scores_list[i]
    matrix[i] = {
      math.max(s.nL, eps),
      math.max(s.nQ, eps),
      math.max(s.nC, eps)
    }
  end
  return calculate_entropy_weights(matrix, n)
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
