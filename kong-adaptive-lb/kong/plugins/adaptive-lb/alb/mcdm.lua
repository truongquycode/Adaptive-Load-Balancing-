local _M = {}

local AHP_WEIGHTS = { 0.648, 0.230, 0.122 }

local function clamp01(v)
  if v < 0.0 then return 0.0 end
  if v > 1.0 then return 1.0 end
  return v
end

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
    diversity[j] = math.max(0.0, 1.0 - entropy)
    sum_diversity = sum_diversity + diversity[j]
  end
  
  if sum_diversity <= eps then
    return { AHP_WEIGHTS[1], AHP_WEIGHTS[2], AHP_WEIGHTS[3] }
  end
  
  return {
    diversity[1] / sum_diversity,
    diversity[2] / sum_diversity,
    diversity[3] / sum_diversity
  }
end

function _M.calculate_entropy_weights_from_scores(scores_list, n)
  local eps = 1e-9
  local matrix = {}
  for i = 1, n do
    local s = scores_list[i]
    matrix[i] = {
      clamp01(s.nL) + eps,
      clamp01(s.nQ) + eps,
      clamp01(s.nC) + eps
    }
  end
  return calculate_entropy_weights(matrix, n)
end

return _M
