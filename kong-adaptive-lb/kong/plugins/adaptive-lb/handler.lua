local metrics_poller = require("kong.plugins.adaptive-lb.alb.metrics_poller")
local p2c_balancer = require("kong.plugins.adaptive-lb.alb.p2c_balancer")

local AdaptiveLbHandler = {
  PRIORITY = 1000,
  VERSION = "1.0",
}

function AdaptiveLbHandler:init_worker()
  -- To prevent duplicate polling when Kong runs multiple workers,
  -- we only let worker ID 0 run the metrics poller (Single-controller mode).
  if ngx.worker.id() == 0 then
    -- We pass a dummy config here, but in Kong 3.x, init_worker doesn't have route configs.
    -- The poller will lazily read the first valid config it sees or use default values.
    -- Or we can have the poller fetch from a shared dictionary that gets populated on the first request.
    -- For this implementation, we will use a global timer inside the access phase if not started,
    -- or just rely on a config singleton. Wait, init_worker doesn't know the route config yet!
    -- Workaround: We start the timer lazily on the first request, but ensure it only runs once per cluster/worker.
  end
end

-- Lazy startup for metrics poller with config context
local timer_started = false

function AdaptiveLbHandler:access(config)
  -- 1. Ensure metrics poller is running (lazy start to access `config`)
  if not timer_started and ngx.worker.id() == 0 then
    timer_started = true
    metrics_poller.start(config)
  end

  -- 2. P2C Routing
  local target = p2c_balancer.choose(config)
  
  if target then
    -- 3. Set upstream target
    kong.service.set_target(target.host, target.port)
    
    -- 4. Atomic Inflight Increment
    local inflight_dict = ngx.shared.alb_inflight
    local key = target.host .. ":" .. target.port
    local new_val, err = inflight_dict:incr(key, 1, 0)
    
    -- Pass context to log phase
    kong.ctx.plugin.selected_target_key = key
  else
    kong.log.err("[AdaptiveLB] No available target found. Falling back to default routing or dropping.")
    -- If no target (e.g. all hard capped), return 503
    return kong.response.exit(503, "No available upstreams (Adaptive LB Hard Cap)")
  end
end

function AdaptiveLbHandler:log(config)
  local key = kong.ctx.plugin.selected_target_key
  if key then
    local inflight_dict = ngx.shared.alb_inflight
    local scores_dict = ngx.shared.alb_scores
    
    -- Atomic Inflight Decrement
    local new_val, err = inflight_dict:incr(key, -1, 0)
    if new_val and new_val < 0 then
      inflight_dict:set(key, 0)
    end
    
    -- Tăng đếm tổng số request hoàn thành để Dynamic Weight tính RPS
    scores_dict:incr("__global_completed__", 1, 0)
  end
end

return AdaptiveLbHandler
