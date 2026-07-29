local window = require "kong.plugins.adaptive-lb.alb.sliding_window"
local ewma = require "kong.plugins.adaptive-lb.alb.ewma"
local norm = require "kong.plugins.adaptive-lb.alb.normalization"
local dyn_w = require "kong.plugins.adaptive-lb.alb.dynamic_weight"
local pid = require "kong.plugins.adaptive-lb.alb.pid"

local _M = {}

local pid_states = {}
local ema_states = {}

-- config từ schema
function _M.calculate_score(instance_id, metrics, config, now)
  -- 1. Nếu không có metrics (chết/offline), trả về max score
  if not metrics or metrics.failed then
    return {
      instance_id = instance_id,
      ewma_lat = 0,
      nL = 1.0,
      nQ = 1.0,
      nC = 1.0,
      base_score = 1.0,
      pid_penalty = 0.0,
      final_score = 20.0,
      updated_at = now
    }
  end

  -- 2. Percentile Snapshot
  local snap = window.get_snapshot(instance_id)
  local sys_snap = window.get_system_snapshot()

  local p50 = snap.p50
  local l_raw = metrics.latency > 0 and metrics.latency or p50

  -- 3. Adaptive EWMA
  local ema = ema_states[instance_id] or { lat = p50 }
  ema.lat = ewma.smooth(ema.lat, l_raw, config.polling_interval_ms, config.ewma_tau_min, config.ewma_tau_max, config.ewma_k, p50)
  ema_states[instance_id] = ema
  local ewma_lat = ema.lat

  -- 4. Lấy System Bounds để chuẩn hóa
  local sys_p5 = sys_snap.p5
  local sys_p95 = sys_snap.p95
  
  if sys_p95 <= sys_p5 or sys_p5 < 1.0 then
    sys_p5 = 30.0
    sys_p95 = 300.0
  end
  
  local lat_range = sys_p95 - sys_p5
  if lat_range < 80.0 then
    local mid = (sys_p5 + sys_p95) * 0.5
    sys_p5 = math.max(1.0, mid - 40.0)
    sys_p95 = sys_p5 + 80.0
  end
  local inv_range = 1.0 / (sys_p95 - sys_p5)

  -- 5. Chuẩn hóa [0,1]
  local nL = norm.normalize_latency(ewma_lat, sys_p5, inv_range)
  local nQ = norm.normalize_queue(metrics.queue, snap.qP99)
  local nC = norm.normalize_cpu(metrics.cpu)

  -- 6. Tính Base Score (MCDM)
  local alpha, beta, gamma = dyn_w.get_weights()
  local base_score = (alpha * nL) + (beta * nQ) + (gamma * nC)

  -- 7. Tính PID Penalty
  local norm_p75 = norm.normalize_latency(sys_snap.p75, sys_p5, inv_range)
  
  if not pid_states[instance_id] then
    pid_states[instance_id] = pid.new(config.pid_kp, config.pid_ki, config.pid_kd, config.pid_min_i, config.pid_max_i, config.pid_error_deadband, config.pid_tau_d, config.pid_lambda, config.pid_kappa)
  end
  
  local penalty = pid.update(pid_states[instance_id], norm_p75, nL, config.polling_interval_ms)

  local final_score = base_score + penalty

  return {
    instance_id = instance_id,
    ewma_lat = ewma_lat,
    nL = nL,
    nQ = nQ,
    nC = nC,
    base_score = base_score,
    pid_penalty = penalty,
    final_score = final_score,
    updated_at = now
  }
end

return _M
