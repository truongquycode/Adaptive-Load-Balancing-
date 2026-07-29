local _M = {}

local rr_counter = 0
local first_seen = {}
local last_selected = {}
local last_health_weight = 0.50
local last_load_weight = 0.50

local function clamp(val, min, max)
  if val < min then return min end
  if val > max then return max end
  return val
end

local function normalize(value, min_val, max_val, min_range)
  local range = max_val - min_val
  if range <= min_range then return 0.5 end
  return clamp((value - min_val) / range, 0.0, 1.0)
end

local function ratio_cost(value, start_val, full_val)
  local span = full_val - start_val
  if span < 1e-9 then span = 1e-9 end
  return clamp((value - start_val) / span, 0.0, 1.0)
end

local function relative_spread(values)
  if #values == 0 then return 0.0 end
  local min_v = math.huge
  local max_v = -math.huge
  local sum = 0.0
  for _, v in ipairs(values) do
    if v < min_v then min_v = v end
    if v > max_v then max_v = v end
    sum = sum + v
  end
  local mean = math.abs(sum / #values)
  if mean < 1e-9 then mean = 1e-9 end
  return (max_v - min_v) / mean
end

-- config từ schema
function _M.choose(config)
  local targets = config.targets
  local n = #targets
  if n == 0 then return nil end
  if n == 1 then return targets[1] end

  local scores = ngx.shared.alb_scores
  local inflight = ngx.shared.alb_inflight
  local now = ngx.now() * 1000

  local total_inflight = 0
  local sum_capacity = 0.0
  local instance_map = {}
  
  for i = 1, n do
    local t = targets[i]
    local key = t.host .. ":" .. t.port
    instance_map[key] = t
    
    local cap = t.capacity_weight or 1.0
    sum_capacity = sum_capacity + cap
    local cur_inflight = inflight:get(key) or 0
    total_inflight = total_inflight + cur_inflight
  end
  
  if sum_capacity < 1e-9 then sum_capacity = 1e-9 end
  local avg_capacity = sum_capacity / math.max(1, n)

  local raw_nodes = {}
  local health_raws = {}
  local load_raws = {}
  
  local all_warmup = true

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

    local final_score = scores:get(key)
    local ewma_lat = scores:get(key .. "_lat")
    local updated_at = scores:get(key .. "_upd")

    local health_raw = final_score and math.max(0.0, final_score) or 0.50
    local ewma_latency_ms = ewma_lat and math.max(0.0, ewma_lat) or 0.0
    local age_ms = updated_at and math.max(0, now - updated_at) or math.huge
    
    local cap = t.capacity_weight or 1.0
    local cap_share = cap / sum_capacity
    local expected = math.max(config.routing_min_expected_inflight, total_inflight * cap_share)
    
    local cur_inflight = inflight:get(key) or 0
    local load_raw = cur_inflight / math.max(expected, 1e-9)
    
    local factor = clamp(cap / math.max(avg_capacity, 1e-9), config.routing_capacity_cap_factor_min, config.routing_capacity_cap_factor_max)
    local hard_inflight_cap = math.max(config.routing_hard_inflight_cap_min, math.floor(config.routing_hard_inflight_cap * factor + 0.5))
    
    local target_lat = config.routing_absolute_latency_target_ms
    local crit_lat = math.max(target_lat + 1.0, config.routing_absolute_latency_critical_ms)
    local absolute_latency_cost = ratio_cost(ewma_latency_ms, target_lat, crit_lat)

    local hard_excluded = false
    local reason = "NORMAL"
    if not final_score then
      hard_excluded = true
      reason = "NO_METRICS"
    elseif age_ms > config.stale_hard_ms then
      hard_excluded = true
      reason = "STALE"
    elseif health_raw >= config.routing_unhealthy_score_cutoff then
      hard_excluded = true
      reason = "UNHEALTHY_SCORE"
    elseif cur_inflight >= hard_inflight_cap then
      hard_excluded = true
      reason = "HARD_INFLIGHT_CAP"
    end
    
    local stale_penalty = 0.0
    if age_ms ~= math.huge and age_ms > config.stale_soft_ms then
      local span = math.max(1.0, config.stale_hard_ms - config.stale_soft_ms)
      local ratio = clamp((age_ms - config.stale_soft_ms) / span, 0.0, 1.0)
      stale_penalty = config.routing_stale_penalty_weight * ratio
    end

    table.insert(raw_nodes, {
      key = key,
      health_raw = health_raw,
      load_raw = load_raw,
      absolute_latency_cost = absolute_latency_cost,
      stale_penalty = stale_penalty,
      cap = cap,
      inflight = cur_inflight,
      hard_inflight_cap = hard_inflight_cap,
      hard_excluded = hard_excluded,
      reason = reason
    })
    
    table.insert(health_raws, health_raw)
    table.insert(load_raws, load_raw)
  end

  local min_health = math.huge
  local max_health = -math.huge
  local min_load = math.huge
  local max_load = -math.huge
  
  for _, hr in ipairs(health_raws) do
    if hr < min_health then min_health = hr end
    if hr > max_health then max_health = hr end
  end
  for _, lr in ipairs(load_raws) do
    if lr < min_load then min_load = lr end
    if lr > max_load then max_load = lr end
  end
  
  local health_spread = relative_spread(health_raws)
  local load_spread = relative_spread(load_raws)
  
  local target_health_weight = health_spread / (health_spread + load_spread + 1e-9)
  target_health_weight = clamp(target_health_weight, config.routing_min_health_weight, config.routing_max_health_weight)
  
  local alpha_w = clamp(config.routing_weight_ema_alpha, 0.0, 1.0)
  last_health_weight = clamp(last_health_weight + alpha_w * (target_health_weight - last_health_weight), config.routing_min_health_weight, config.routing_max_health_weight)
  local health_weight = last_health_weight
  local load_weight = 1.0 - health_weight

  local all_hard_excluded = true
  for _, node in ipairs(raw_nodes) do
    if not node.hard_excluded then all_hard_excluded = false end
  end
  
  local is_low_load = (total_inflight <= config.routing_low_load_inflight) and
                      (health_spread <= config.routing_low_load_health_spread) and
                      (load_spread <= config.routing_low_load_load_spread)

  local mode = "NORMAL_P2C"
  if all_warmup or is_low_load then
    mode = "LOW_LOAD_RR"
  elseif all_hard_excluded then
    mode = "ALL_HARD_EXCLUDED_FALLBACK"
  elseif health_weight >= config.routing_dominant_threshold then
    mode = "HEALTH_DOMINANT"
  elseif load_weight >= config.routing_dominant_threshold then
    mode = "LOAD_DOMINANT"
  end

  local all_costs = {}
  local eligible = {}
  
  for _, node in ipairs(raw_nodes) do
    local health_cost = normalize(node.health_raw, min_health, max_health, config.routing_min_routing_norm_range)
    local load_cost = normalize(node.load_raw, min_load, max_load, config.routing_min_routing_norm_range)
    
    local overload_p = config.routing_overload_penalty_weight * ratio_cost(node.load_raw, config.routing_overload_start_ratio, config.routing_overload_full_ratio)
    local cap_p = config.routing_cap_pressure_penalty_weight * ratio_cost(node.inflight / math.max(1.0, node.hard_inflight_cap), config.routing_cap_pressure_start_ratio, config.routing_cap_pressure_full_ratio)
    local abs_health_p = config.routing_absolute_health_penalty_weight * ratio_cost(node.health_raw, config.routing_absolute_health_start, config.routing_absolute_health_full)
    local abs_lat_p = config.routing_absolute_latency_penalty_weight * node.absolute_latency_cost
    
    local final_cost = (health_weight * health_cost) + (load_weight * load_cost) + overload_p + cap_p + abs_health_p + abs_lat_p + node.stale_penalty

    local cost_obj = {
      key = node.key,
      final_cost = final_cost,
      load_raw = node.load_raw,
      cap = node.cap,
      hard_excluded = node.hard_excluded,
      reason = node.reason,
      inflight = node.inflight,
      absolute_latency_cost = node.absolute_latency_cost
    }
    table.insert(all_costs, cost_obj)
    if not node.hard_excluded then
      table.insert(eligible, cost_obj)
    end
  end

  if mode == "LOW_LOAD_RR" then
    rr_counter = rr_counter + 1
    local idx = (rr_counter % n) + 1
    if idx == 0 then idx = n end -- prevent modulo 0 indexing issue
    last_selected[all_costs[idx].key] = now
    return instance_map[all_costs[idx].key]
  end

  local function better(a, b)
    if a.final_cost < b.final_cost then return a end
    if b.final_cost < a.final_cost then return b end
    if a.load_raw < b.load_raw then return a end
    if b.load_raw < a.load_raw then return b end
    if a.cap >= b.cap then return a else return b end
  end

  -- Maybe Probe
  if mode ~= "LOAD_DOMINANT" and mode ~= "ALL_HARD_EXCLUDED_FALLBACK" then
    local max_load_raw = max_load
    local max_abs_lat = -math.huge
    for _, c in ipairs(all_costs) do
      if c.absolute_latency_cost > max_abs_lat then max_abs_lat = c.absolute_latency_cost end
    end
    
    local is_stress = (max_load_raw > config.routing_probe_max_load_raw) or 
                      (max_abs_lat > config.routing_probe_max_absolute_latency_cost) or 
                      (total_inflight > math.floor(config.routing_hard_inflight_cap * math.max(1, n) * config.routing_probe_max_total_inflight_ratio))
    
    if not is_stress then
      -- sort all_costs by final_cost reversed
      local ordered = {}
      for _, c in ipairs(all_costs) do table.insert(ordered, c) end
      table.sort(ordered, function(a, b) return a.final_cost > b.final_cost end)
      
      for _, c in ipairs(ordered) do
        if c.hard_excluded and c.reason ~= "HARD_INFLIGHT_CAP" and c.inflight < config.routing_hard_inflight_cap then
          if c.final_cost <= config.routing_probe_max_final_cost and c.absolute_latency_cost <= config.routing_probe_max_absolute_latency_cost then
            local last = last_selected[c.key] or 0
            if (now - last) >= config.routing_probe_interval_ms then
              if math.random() < config.routing_probe_probability then
                last_selected[c.key] = now
                return instance_map[c.key]
              end
            end
          end
        end
      end
    end
  end

  -- P2C
  local candidates = #eligible > 0 and eligible or all_costs
  local m = #candidates
  
  local selected_cost
  if m == 1 then
    selected_cost = candidates[1]
  else
    local idx1 = math.random(1, m)
    local idx2 = math.random(1, m - 1)
    if idx2 >= idx1 then idx2 = idx2 + 1 end
    
    local c1 = candidates[idx1]
    local c2 = candidates[idx2]
    selected_cost = better(c1, c2)
  end

  if not selected_cost then
    -- Least cost fallback
    local best = all_costs[1]
    for i = 2, #all_costs do
      if all_costs[i].final_cost < best.final_cost then
        best = all_costs[i]
      end
    end
    selected_cost = best
  end

  last_selected[selected_cost.key] = now
  return instance_map[selected_cost.key]
end

return _M
