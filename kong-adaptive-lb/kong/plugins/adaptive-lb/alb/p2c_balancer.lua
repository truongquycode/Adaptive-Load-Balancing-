local _M = {}

local rr_counter = 0
local first_seen = {}
local last_selected = {}

-- config từ schema
function _M.choose(config)
  local targets = config.targets
  local n = #targets
  if n == 0 then return nil end
  if n == 1 then return targets[1] end

  local scores = ngx.shared.alb_scores
  local inflight = ngx.shared.alb_inflight
  local now = ngx.now() * 1000

  -- 1. Tính toán Routing Cost cho tất cả các node (giống RoutingCostCalculator)
  local all_costs = {}
  local eligible = {}
  
  local all_warmup = true
  local max_load_raw = 0.0
  local max_abs_lat = 0.0
  local total_inflight = 0
  
  local is_low_load = true

  for i = 1, n do
    local t = targets[i]
    local key = t.host .. ":" .. t.port
    
    local fs = first_seen[key]
    if not fs then
      first_seen[key] = now
      fs = now
    end
    if (now - fs) >= config.routing_warmup_ms then
      all_warmup = false
    end

    local cur_inflight = inflight:get(key) or 0
    total_inflight = total_inflight + cur_inflight
    local score = scores:get(key) or config.score_null

    local cap = t.capacity_weight or 100.0
    local load_raw = cur_inflight / cap
    
    local inflight_penalty = load_raw * math.log(1.0 + load_raw)
    local final_cost = score * (1.0 + inflight_penalty)
    local abs_lat = score * (1.0 + inflight_penalty) -- simplified version of absoluteLatencyCost
    
    if load_raw > max_load_raw then max_load_raw = load_raw end
    if abs_lat > max_abs_lat then max_abs_lat = abs_lat end

    if load_raw >= config.routing_low_load_inflight or score >= 0.5 then
      is_low_load = false
    end

    local hard_excluded = false
    local reason = "OK"

    if cur_inflight >= config.routing_hard_inflight_cap then
      hard_excluded = true
      reason = "HARD_INFLIGHT_CAP"
    elseif score > 1.8 then
      hard_excluded = true
      reason = "BAD_HEALTH"
    end

    local cost_obj = {
      target = t,
      key = key,
      inflight = cur_inflight,
      score = score,
      load_raw = load_raw,
      final_cost = final_cost,
      abs_lat = abs_lat,
      hard_excluded = hard_excluded,
      reason = reason
    }
    
    table.insert(all_costs, cost_obj)
    if not hard_excluded then
      table.insert(eligible, cost_obj)
    end
  end
  
  -- 2. Warmup & Low Load RR
  if all_warmup or is_low_load then
    rr_counter = rr_counter + 1
    local idx = (rr_counter % n) + 1
    last_selected[all_costs[idx].key] = now
    return all_costs[idx].target
  end
  
  -- 3. Maybe Probe (Probe Recovery)
  -- Không probe nếu đang stress
  local is_stress = max_load_raw > 1.2 or max_abs_lat > 2.0 or total_inflight > (config.routing_hard_inflight_cap * n * 0.8)
  
  if not is_stress then
    for _, c in ipairs(all_costs) do
      if c.hard_excluded and c.reason ~= "HARD_INFLIGHT_CAP" and c.inflight < config.routing_hard_inflight_cap then
        local last = last_selected[c.key] or 0
        if (now - last) >= config.routing_probe_interval_ms then
          if math.random() < config.routing_probe_probability then
            last_selected[c.key] = now
            return c.target
          end
        end
      end
    end
  end

  -- 4. P2C trên danh sách eligible
  local candidates = #eligible > 0 and eligible or all_costs
  local m = #candidates
  
  if m == 1 then
    last_selected[candidates[1].key] = now
    return candidates[1].target
  end

  local idx1 = math.random(1, m)
  local idx2 = math.random(1, m - 1)
  if idx2 >= idx1 then idx2 = idx2 + 1 end
  
  local c1 = candidates[idx1]
  local c2 = candidates[idx2]
  
  local best
  if c1.final_cost <= c2.final_cost then
    best = c1
  else
    best = c2
  end

  last_selected[best.key] = now
  return best.target
end

return _M
