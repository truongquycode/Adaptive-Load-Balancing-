local mcdm = require "kong.plugins.adaptive-lb.alb.mcdm"

local _M = {}

local AHP_ALPHA = 0.648
local AHP_BETA = 0.230
local AHP_GAMMA = 0.122

local current_alpha = AHP_ALPHA
local current_beta = AHP_BETA
local current_gamma = AHP_GAMMA

local update_mode = 0.0 -- 0=frozen, 1=dynamic

function _M.get_weights()
  return current_alpha, current_beta, current_gamma
end

function _M.get_update_mode()
  return update_mode
end

function _M.reset_weights()
  current_alpha = AHP_ALPHA
  current_beta = AHP_BETA
  current_gamma = AHP_GAMMA
  update_mode = 0.0
end

local function clamp(v, min, max)
  if v < min then return min end
  if v > max then return max end
  return v
end

-- Tính toán MCDM dựa trên scores (nL, nQ, nC) của tất cả instance
function _M.update_weights(scores, rps, config)
  -- Guard: Không đủ traffic thì dùng AHP
  if rps < 5.0 or #scores < 2 then
    _M.reset_weights()
    return
  end

  -- Kiểm tra độ ổn định
  local avg_q, avg_c = 0.0, 0.0
  local min_l, max_l = math.huge, -math.huge
  
  for _, s in ipairs(scores) do
    avg_q = avg_q + s.nQ
    avg_c = avg_c + s.nC
    if s.nL < min_l then min_l = s.nL end
    if s.nL > max_l then max_l = s.nL end
  end
  
  local n = #scores
  avg_q = avg_q / n
  avg_c = avg_c / n

  if avg_q < 0.2 and avg_c < 0.5 and (max_l - min_l) < 0.1 then
    _M.reset_weights()
    return
  end

  -- Tính EWM
  local ewm = mcdm.calculate_entropy_weights_from_scores(scores, n)
  
  local blend = 0.5
  local target_alpha = blend * ewm[1] + (1 - blend) * AHP_ALPHA
  local target_beta = blend * ewm[2] + (1 - blend) * AHP_BETA
  local target_gamma = blend * ewm[3] + (1 - blend) * AHP_GAMMA

  local sum_target = target_alpha + target_beta + target_gamma
  target_alpha = target_alpha / sum_target
  target_beta = target_beta / sum_target
  target_gamma = target_gamma / sum_target

  local delta = (math.abs(target_alpha - current_alpha) + math.abs(target_beta - current_beta) + math.abs(target_gamma - current_gamma)) / 3.0
  local ema_alpha = 0.05 + (0.2 - 0.05) * clamp(delta * 3.0, 0.0, 1.0)

  local new_a = ema_alpha * target_alpha + (1 - ema_alpha) * current_alpha
  local new_b = ema_alpha * target_beta + (1 - ema_alpha) * current_beta
  local new_c = ema_alpha * target_gamma + (1 - ema_alpha) * current_gamma

  -- Soft bounds
  new_a = clamp(new_a, 0.3, 0.85)
  new_b = clamp(new_b, 0.1, 0.5)
  new_c = clamp(new_c, 0.05, 0.35)

  local sum_new = new_a + new_b + new_c
  current_alpha = new_a / sum_new
  current_beta = new_b / sum_new
  current_gamma = new_c / sum_new
  
  update_mode = 1.0
end

return _M
