local _M = {}

-- Helper function to evaluate target based on P2C + Routing Cost logic
-- Equivalent to SCG AdaptiveLoadBalancer.java
local function get_routing_cost(target, config, scores, inflight_dict)
  local key = target.host .. ":" .. target.port
  local current_inflight = inflight_dict:get(key) or 0
  
  -- Hard Cap Protection
  if current_inflight >= config.routing_hard_inflight_cap then
    return math.huge -- Infinity, unpickable
  end
  
  -- Low Load fast-path (Round-Robin bypass) -> we approximate by returning very low cost if inflight is small
  -- In SCG, if inflight < low_load, it uses RR. In P2C, we can just say if it's < low_load, cost is effectively 0.
  if current_inflight < config.routing_low_load_inflight then
    -- Return just the inflight, making it highly preferable
    return current_inflight / target.capacity_weight 
  end
  
  -- Normal P2C Cost Calculation
  -- Get pre-calculated final score from Control Plane
  local health_score = scores:get(key) or config.score_null
  
  -- Cost = (Inflight / Capacity) * Score (This is the SCG formula)
  local cost = (current_inflight / target.capacity_weight) * health_score
  
  -- Note: Probe behavior is complex. SCG occasionally probes bad targets.
  -- To keep it lightweight in Data Plane, if health_score is very bad (high cost),
  -- we rely on math.random in P2C. To explicitly probe, we could force a random pick with 0.005 probability.
  local r = math.random()
  if r < config.routing_probe_probability then
    -- Force this target to win if it's a probe request
    return -1
  end

  return cost
end

function _M.choose(config)
  local targets = config.targets
  local n = #targets
  if n == 0 then return nil end
  if n == 1 then return targets[1] end
  
  local scores = ngx.shared.alb_scores
  local inflight = ngx.shared.alb_inflight
  
  -- P2C: Randomly select 2 distinct targets
  local idx1 = math.random(1, n)
  local idx2 = math.random(1, n - 1)
  if idx2 >= idx1 then idx2 = idx2 + 1 end
  
  local t1 = targets[idx1]
  local t2 = targets[idx2]
  
  local cost1 = get_routing_cost(t1, config, scores, inflight)
  local cost2 = get_routing_cost(t2, config, scores, inflight)
  
  if cost1 == math.huge and cost2 == math.huge then
    -- Both capped, we must try to find another or drop. 
    -- For simplicity, drop (return nil) which causes 503.
    return nil
  end
  
  if cost1 <= cost2 then
    return t1
  else
    return t2
  end
end

return _M
