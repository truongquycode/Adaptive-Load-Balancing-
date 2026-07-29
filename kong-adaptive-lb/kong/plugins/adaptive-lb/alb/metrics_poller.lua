local http = require "resty.http"
local window = require "kong.plugins.adaptive-lb.alb.sliding_window"
local score_calc = require "kong.plugins.adaptive-lb.alb.score_calculator"
local dyn_w = require "kong.plugins.adaptive-lb.alb.dynamic_weight"

local _M = {}

local last_completed = 0
local last_time = 0

-- Auto-Healing Inflight (Tránh leak Inflight khi worker crash)
local function auto_heal_inflight(target_key, actual_connections)
  local inflight_dict = ngx.shared.alb_inflight
  local current_inflight = inflight_dict:get(target_key) or 0
  if current_inflight > 0 and actual_connections == 0 then
    inflight_dict:set(target_key, math.floor(current_inflight / 2))
  end
end

local function poll_backend(host, port)
  local httpc = http.new()
  httpc:set_timeout(500)
  
  -- Lấy metrics từ Spring Boot Actuator Prometheus (giống SCG Java)
  local res, err = httpc:request_uri("http://" .. host .. ":" .. tostring(port) .. "/actuator/prometheus", {
    method = "GET"
  })
  
  if not res then
    return { failed = true }
  end
  
  local body = res.body
  -- Parse nhanh bằng regex string match
  local p95_lat = tonumber(body:match('http_server_requests_seconds_max{.-} ([%d%.E%-]+)') or "0.0") * 1000.0
  local queue = tonumber(body:match('tomcat_threads_busy_threads ([%d%.]+)') or "0.0")
  local cpu = tonumber(body:match('system_cpu_usage ([%d%.]+)') or "0.0")
  
  return {
    latency = p95_lat,
    queue = queue,
    cpu = cpu,
    active_requests = queue
  }
end

local function do_poll(premature, config)
  if premature then return end
  
  local now = ngx.now() * 1000.0 -- ms
  local scores_dict = ngx.shared.alb_scores
  
  -- 1. Tính RPS dựa trên __global_completed__ do handler.lua ghi nhận
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
  local raw_metrics = {}
  
  -- 2. Poll Backend & Thêm vào Sliding Window
  for _, t in ipairs(targets) do
    local key = t.host .. ":" .. t.port
    local metrics = poll_backend(t.host, t.port)
    raw_metrics[key] = metrics
    
    if not metrics.failed then
      window.add_metrics(key, metrics.latency, metrics.queue)
      auto_heal_inflight(key, metrics.active_requests)
    end
  end
  
  -- 3. Gọi ScoreCalculator cho tất cả để lấy intermediate scores (nL, nQ, nC)
  local intermediate_scores = {}
  local keys_list = {}
  for _, t in ipairs(targets) do
    local key = t.host .. ":" .. t.port
    local s = score_calc.calculate_score(key, raw_metrics[key], config, now)
    table.insert(intermediate_scores, s)
    table.insert(keys_list, key)
  end
  
  -- 4. Cập nhật Dynamic Weights (MCDM) với RPS Guard
  dyn_w.update_weights(intermediate_scores, actual_rps, config)
  local alpha, beta, gamma = dyn_w.get_weights()
  
  -- Lưu metrics của MCDM để exporter đọc
  scores_dict:set("__mcdm_alpha__", alpha)
  scores_dict:set("__mcdm_beta__", beta)
  scores_dict:set("__mcdm_gamma__", gamma)
  scores_dict:set("__mcdm_mode__", dyn_w.get_update_mode())
  
  -- 5. Tính Final Score với MCDM và PID
  for _, s in ipairs(intermediate_scores) do
    -- Chỉ tính lại base_score và final_score cho các node ALIVE (score < 20.0)
    -- Nếu node bị fail, score_calculator đã trả về final_score = 20.0, ta không được ghi đè!
    if s.final_score < 20.0 then
      s.base_score = (alpha * s.nL) + (beta * s.nQ) + (gamma * s.nC)
      s.final_score = s.base_score + s.pid_penalty
    end
    
    -- Lưu Final Score vào Shared Memory để Data Plane (p2c_balancer) dùng
    scores_dict:set(s.instance_id, s.final_score)
    
    -- Lưu các thành phần phụ để Grafana Exporter dùng
    scores_dict:set(s.instance_id .. "_nl", s.nL)
    scores_dict:set(s.instance_id .. "_nq", s.nQ)
    scores_dict:set(s.instance_id .. "_nc", s.nC)
    scores_dict:set(s.instance_id .. "_base", s.base_score)
    scores_dict:set(s.instance_id .. "_pid", s.pid_penalty)
    scores_dict:set(s.instance_id .. "_lat", s.ewma_lat)
  end
  
  -- Reschedule
  ngx.timer.at(config.polling_interval_ms / 1000.0, do_poll, config)
end

function _M.start(config)
  ngx.timer.at(0, do_poll, config)
end

return _M
