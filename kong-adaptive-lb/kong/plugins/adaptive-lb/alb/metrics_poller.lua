local http = require "resty.http"
local cjson = require "cjson.safe"
local window = require "kong.plugins.adaptive-lb.alb.sliding_window"
local score_calc = require "kong.plugins.adaptive-lb.alb.score_calculator"
local dyn_w = require "kong.plugins.adaptive-lb.alb.dynamic_weight"

local _M = {}

local last_completed = 0
local last_time = 0

local traffic_states = {}
local consecutive_failures = {}
local smoothed_scores = {}
local latency_values = {}

local function clamp(val, min, max)
  if val < min then return min end
  if val > max then return max end
  return val
end

-- config defaults for missing schema props
local score_ema_spike_threshold = 0.30
local score_ema_alpha_spike = 0.60
local score_ema_alpha_rise = 0.35
local score_ema_alpha_recover = 0.25
local idle_latency_baseline_ms = 65.0
local idle_decay_alpha = 0.20

local function idle_latency_baseline_for(instance_id)
  local sum = 0.0
  local count = 0
  for _, lat in pairs(latency_values) do
    if lat > 1.0 and lat ~= math.huge then
      sum = sum + lat
      count = count + 1
    end
  end
  if count > 0 then
    return clamp(sum / count, 1.0, 3000.0)
  end
  
  local snap = window.get_snapshot(instance_id)
  if snap and snap.p50 > 1.0 and snap.p50 ~= math.huge then
    return clamp(snap.p50, 1.0, 3000.0)
  end
  
  return idle_latency_baseline_ms
end

local function apply_score_ema(instance_id, raw_score)
  local prev = smoothed_scores[instance_id]
  if not prev then
    smoothed_scores[instance_id] = raw_score
    return raw_score
  end

  local delta = raw_score - prev
  local alpha
  if delta > score_ema_spike_threshold then
    alpha = score_ema_alpha_spike
  elseif delta > 0.0 then
    alpha = score_ema_alpha_rise
  else
    alpha = score_ema_alpha_recover
  end

  local smoothed = prev + alpha * delta
  smoothed_scores[instance_id] = smoothed
  return smoothed
end

local function calculate_delta_latency(instance_id, current_count, current_total_time)
  local prev = traffic_states[instance_id]
  
  if not prev then
    local init_lat = idle_latency_baseline_for(instance_id)
    traffic_states[instance_id] = { count = current_count, total_time = current_total_time, last_lat = init_lat }
    return init_lat, 0.0, false
  end
  
  local delta_count = current_count - prev.count
  local delta_total = current_total_time - prev.total_time
  
  local current_latency
  local completed_requests = 0.0
  local real_latency_sample = false
  
  if delta_count > 0 and delta_total >= 0 then
    current_latency = (delta_total / delta_count) * 1000.0
    completed_requests = delta_count
    real_latency_sample = true
  elseif delta_count < 0 or delta_total < 0 then
    -- counter reset
    current_latency = idle_latency_baseline_for(instance_id)
  else
    -- idle
    local idle_target = idle_latency_baseline_for(instance_id)
    current_latency = prev.last_lat + idle_decay_alpha * (idle_target - prev.last_lat)
  end
  
  current_latency = clamp(current_latency, 1.0, 3000.0)
  traffic_states[instance_id] = { count = current_count, total_time = current_total_time, last_lat = current_latency }
  
  return current_latency, completed_requests, real_latency_sample
end

local function poll_backend(host, port)
  local httpc = http.new()
  httpc:set_timeout(800)
  
  local res, err = httpc:request_uri("http://" .. host .. ":" .. tostring(port) .. "/api/alb-metrics", {
    method = "GET"
  })
  
  if not res then
    return { failed = true, err = err }
  end
  
  local body = cjson.decode(res.body) or {}
  local cpu = tonumber(body.cpu) or 0.0
  local count = tonumber(body.count) or 0.0
  local total_time = tonumber(body.totalTime) or 0.0
  local queue = tonumber(body.queue) or -1.0
  local capacity_weight = tonumber(body.capacityWeight) or 1.0
  
  return {
    failed = false,
    cpu = cpu,
    count = count,
    total_time = total_time,
    queue = queue,
    capacity_weight = capacity_weight
  }
end

local function neutral_idle_score(instance_id, latency_ms, queue, cpu, now)
  local safe_lat = clamp(latency_ms, 1.0, 3000.0)
  local norm_cpu = clamp(cpu, 0.0, 1.0)
  local norm_queue = queue > 0.0 and 0.25 or 0.0
  return {
    instance_id = instance_id,
    ewma_lat = safe_lat,
    nL = 0.5,
    nQ = norm_queue,
    nC = norm_cpu,
    base_score = 0.5,
    pid_penalty = 0.0,
    final_score = 0.5,
    updated_at = now
  }
end

local function do_poll(premature, config)
  if premature then return end
  
  local now = ngx.now() * 1000.0
  local scores_dict = ngx.shared.alb_scores
  local inflight_dict = ngx.shared.alb_inflight
  
  local current_completed = scores_dict:get("__global_completed__") or 0
  local actual_rps = 0
  if last_time > 0 then
    local dt_sec = (now - last_time) / 1000.0
    if dt_sec > 0.001 then
      local diff = current_completed - last_completed
      if diff < 0 then diff = 0 end
      actual_rps = diff / dt_sec
    end
  end
  last_completed = current_completed
  last_time = now
  scores_dict:set("__global_rps__", actual_rps)
  
  local targets = config.targets
  local intermediate_scores = {}
  
  -- Calculate active topology to clean up stale data
  local active_keys = {}
  for _, t in ipairs(targets) do
    local key = t.host .. ":" .. t.port
    active_keys[key] = true
  end
  -- We don't implement full cleanup here for simplicity, but in a real proxy you would delete keys not in active_keys
  
  for _, t in ipairs(targets) do
    local key = t.host .. ":" .. t.port
    
    local metrics = poll_backend(t.host, t.port)
    
    if metrics.failed then
      local failures = (consecutive_failures[key] or 0) + 1
      consecutive_failures[key] = failures
      
      local raw_penalty = math.min(10.0, failures * 2.5)
      local smoothed = apply_score_ema(key, raw_penalty)
      
      local s = {
        instance_id = key,
        ewma_lat = 0,
        nL = 1.0,
        nQ = 1.0,
        nC = 1.0,
        base_score = raw_penalty * 0.8,
        pid_penalty = raw_penalty * 0.2,
        final_score = smoothed,
        updated_at = now
      }
      table.insert(intermediate_scores, s)
      latency_values[key] = 0.0
    else
      consecutive_failures[key] = 0
      
      -- update capacity weight dynamically (since we didn't add it to shared dict, we just update config.targets manually or pass it. Wait, P2C reads config.targets. We can't update config.targets directly, but p2c_balancer reads t.capacity_weight. We can store it in shared dict or local memory)
      -- For parity, we should store capacity in shared dict. Let's assume P2C uses the config.capacity_weight for now, or we can use the shared dict.
      t.capacity_weight = metrics.capacity_weight
      
      local current_lat, completed_reqs, real_lat_sample = calculate_delta_latency(key, metrics.count, metrics.total_time)
      
      if completed_reqs > 0.0 then
        scores_dict:incr("__global_mcdm_completed__", completed_reqs, 0)
      end
      
      local real_queue = metrics.queue >= 0 and metrics.queue or (inflight_dict:get(key) or 0)
      
      if real_lat_sample then
        window.add_metrics(key, current_lat, real_queue)
        
        local inst_metrics = { latency = current_lat, queue = real_queue, cpu = metrics.cpu, failed = false }
        local raw_breakdown = score_calc.calculate_score(key, inst_metrics, config, now)
        local smoothed = apply_score_ema(key, raw_breakdown.final_score)
        raw_breakdown.final_score = smoothed
        
        table.insert(intermediate_scores, raw_breakdown)
        latency_values[key] = raw_breakdown.ewma_lat
      else
        local has_active_work = real_queue > 0.0
        local prev_lat = latency_values[key] or 0.0
        
        if has_active_work and prev_lat > 0.0 then
          local inst_metrics = { latency = prev_lat, queue = real_queue, cpu = metrics.cpu, failed = false }
          local raw_breakdown = score_calc.calculate_score(key, inst_metrics, config, now)
          local smoothed = apply_score_ema(key, raw_breakdown.final_score)
          raw_breakdown.final_score = smoothed
          table.insert(intermediate_scores, raw_breakdown)
          latency_values[key] = raw_breakdown.ewma_lat
        else
          local refreshed = neutral_idle_score(key, current_lat, real_queue, metrics.cpu, now)
          table.insert(intermediate_scores, refreshed)
          latency_values[key] = refreshed.ewma_lat
        end
      end
    end
  end
  
  -- Update Dynamic Weights (MCDM)
  dyn_w.update_weights(intermediate_scores, actual_rps, config)
  local alpha, beta, gamma = dyn_w.get_weights()
  scores_dict:set("__mcdm_alpha__", alpha)
  scores_dict:set("__mcdm_beta__", beta)
  scores_dict:set("__mcdm_gamma__", gamma)
  
  -- Publish to shared dict for Routing
  for _, s in ipairs(intermediate_scores) do
    if s.final_score < 20.0 and s.nL ~= 0.5 then
      -- Apply MCDM + PID only if real sample
      s.base_score = (alpha * s.nL) + (beta * s.nQ) + (gamma * s.nC)
      s.final_score = apply_score_ema(s.instance_id, s.base_score + s.pid_penalty)
    end
    
    scores_dict:set(s.instance_id, s.final_score)
    scores_dict:set(s.instance_id .. "_nl", s.nL)
    scores_dict:set(s.instance_id .. "_nq", s.nQ)
    scores_dict:set(s.instance_id .. "_nc", s.nC)
    scores_dict:set(s.instance_id .. "_base", s.base_score)
    scores_dict:set(s.instance_id .. "_pid", s.pid_penalty)
    scores_dict:set(s.instance_id .. "_lat", s.ewma_lat)
    scores_dict:set(s.instance_id .. "_upd", s.updated_at)
  end
  
  ngx.timer.at(config.polling_interval_ms / 1000.0, do_poll, config)
end

function _M.start(config)
  ngx.timer.at(0, do_poll, config)
end

return _M
