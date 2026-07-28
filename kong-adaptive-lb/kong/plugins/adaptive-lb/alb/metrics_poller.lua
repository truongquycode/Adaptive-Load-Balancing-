local http = require "resty.http"
local cjson = require "cjson.safe"
local ewma = require "kong.plugins.adaptive-lb.alb.ewma"
local pid = require "kong.plugins.adaptive-lb.alb.pid"
local mcdm = require "kong.plugins.adaptive-lb.alb.mcdm"
local score_calculator = require "kong.plugins.adaptive-lb.alb.score_calculator"

local _M = {}

local pid_states = {}
local ema_states = {}

-- Auto-Healing Inflight
local function auto_heal_inflight(target_key, actual_connections)
  local inflight_dict = ngx.shared.alb_inflight
  local current_inflight = inflight_dict:get(target_key) or 0
  
  -- If Kong thinks there are many inflights but the backend reports 0 active connections,
  -- we likely had a worker crash that leaked inflight counters.
  -- We don't reset blindly, we drift it downwards safely.
  if current_inflight > 0 and actual_connections == 0 then
    -- Safely decay by half
    inflight_dict:set(target_key, math.floor(current_inflight / 2))
  end
end

local function poll_backend(host, port)
  local httpc = http.new()
  httpc:set_timeout(500) -- 500ms timeout
  
  -- Using actuator prometheus to fetch P95 latency, queue size, cpu
  -- For simplicity in Lua, we will call a /actuator/health or mock it if prometheus parsing is too heavy.
  -- SCG fetches Prometheus text and parses it. We can do a simple regex or string match.
  local res, err = httpc:request_uri("http://" .. host .. ":" .. tostring(port) .. "/actuator/prometheus", {
    method = "GET"
  })
  
  if not res then
    return nil, err
  end
  
  local body = res.body
  
  -- Parse metrics (Simplified regex for demonstration of intent, SCG does this)
  local p95_lat = tonumber(body:match('http_server_requests_seconds_max{.-} ([%d%.]+)') or "0.0") * 1000.0
  local queue = tonumber(body:match('tomcat_threads_busy_threads ([%d%.]+)') or "0.0")
  local cpu = tonumber(body:match('system_cpu_usage ([%d%.]+)') or "0.0")
  
  return {
    latency = p95_lat,
    queue = queue,
    cpu = cpu,
    active_requests = queue -- Rough estimate for auto-heal
  }
end

local function do_poll(premature, config)
  if premature then return end
  
  local targets = config.targets
  local raw_metrics = {}
  
  local min_lat = math.huge
  local max_lat = -math.huge
  local min_q = math.huge
  local max_q = -math.huge
  local min_cpu = math.huge
  local max_cpu = -math.huge
  
  for _, t in ipairs(targets) do
    local key = t.host .. ":" .. t.port
    local metrics, err = poll_backend(t.host, t.port)
    
    if metrics then
      raw_metrics[key] = metrics
      
      -- Auto Heal Inflight
      auto_heal_inflight(key, metrics.active_requests)
      
      -- For normalization
      if metrics.latency < min_lat then min_lat = metrics.latency end
      if metrics.latency > max_lat then max_lat = metrics.latency end
      if metrics.queue < min_q then min_q = metrics.queue end
      if metrics.queue > max_q then max_q = metrics.queue end
      if metrics.cpu < min_cpu then min_cpu = metrics.cpu end
      if metrics.cpu > max_cpu then max_cpu = metrics.cpu end
    else
      -- Node failed
      raw_metrics[key] = { failed = true }
    end
  end
  
  local scores_list = {}
  local keys_list = {}
  
  -- Calculate normalizations and EWMA
  for _, t in ipairs(targets) do
    local key = t.host .. ":" .. t.port
    local m = raw_metrics[key]
    
    if m and not m.failed then
      local n_lat = mcdm.normalize(m.latency, min_lat, max_lat, config.math_eps)
      local n_q = mcdm.normalize(m.queue, min_q, max_q, config.math_eps)
      local n_cpu = mcdm.normalize(m.cpu, min_cpu, max_cpu, config.math_eps)
      
      -- EWMA Smoothing
      local ema = ema_states[key] or { lat = 1.0, q = 1.0, cpu = 1.0 }
      ema.lat = ewma.smooth(ema.lat, n_lat, config.polling_interval_ms, config.ewma_tau_min, config.ewma_tau_max, config.ewma_k, config.math_eps)
      ema.q = ewma.smooth(ema.q, n_q, config.polling_interval_ms, config.ewma_tau_min, config.ewma_tau_max, config.ewma_k, config.math_eps)
      ema.cpu = ewma.smooth(ema.cpu, n_cpu, config.polling_interval_ms, config.ewma_tau_min, config.ewma_tau_max, config.ewma_k, config.math_eps)
      ema_states[key] = ema
      
      table.insert(scores_list, { lat_score = ema.lat, queue_score = ema.q, cpu_score = ema.cpu })
      table.insert(keys_list, key)
    else
      -- Stale/Failed node penalty handling would go here.
      -- We omit from MCDM matrix.
    end
  end
  
  -- Calculate MCDM weights
  local alpha, beta, gamma = mcdm.calculate_dynamic_weights(scores_list, config.ahp_lat, config.ahp_queue, config.ahp_cpu, config.math_eps)
  
  -- Calculate Final Score and PID
  local scores_dict = ngx.shared.alb_scores
  
  for i, key in ipairs(keys_list) do
    local s = scores_list[i]
    local base_score = (s.lat_score * alpha) + (s.queue_score * beta) + (s.cpu_score * gamma)
    
    -- PID Controller applies penalty based on deviation from cluster mean
    if not pid_states[key] then
      pid_states[key] = pid.new(config.pid_kp, config.pid_ki, config.pid_kd, config.pid_min_i, config.pid_max_i, config.pid_error_deadband, config.pid_tau_d, config.pid_lambda, config.pid_kappa)
    end
    
    -- We assume setpoint is 1.0 (ideal score)
    local penalty = pid.update(pid_states[key], 1.0, base_score, config.polling_interval_ms)
    
    local final_score = score_calculator.calculate_node_score(s.lat_score, s.queue_score, s.cpu_score, alpha, beta, gamma, penalty)
    
    -- Write to Shared Dict (Atomic, No JSON in Data Plane)
    scores_dict:set(key, final_score)
  end
  
  -- Reschedule
  ngx.timer.at(config.polling_interval_ms / 1000.0, do_poll, config)
end

function _M.start(config)
  -- Initial start
  ngx.timer.at(0, do_poll, config)
end

return _M
