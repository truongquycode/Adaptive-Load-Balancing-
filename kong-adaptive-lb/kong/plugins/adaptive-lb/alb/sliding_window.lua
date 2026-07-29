local _M = {}

local MAX_LAT_SAMPLES = 100
local MAX_GLOBAL_SAMPLES = 160

local global_lat_samples = {}
local global_lat_idx = 1
local global_lat_count = 0

local instance_states = {}

local function percentile(sorted_arr, p)
  local count = #sorted_arr
  if count == 0 then return 0 end
  if count == 1 then return sorted_arr[1] end
  local k = (count - 1) * p + 1
  local f = math.floor(k)
  local c = math.ceil(k)
  if f == c then
    return sorted_arr[k]
  end
  local d0 = sorted_arr[f] * (c - k)
  local d1 = sorted_arr[c] * (k - f)
  return d0 + d1
end

local function add_to_buffer(buffer, val, max_size, state)
  buffer[state.idx] = val
  state.idx = state.idx + 1
  if state.idx > max_size then
    state.idx = 1
  end
  if state.count < max_size then
    state.count = state.count + 1
  end
end

local function get_sorted_copy(buffer, count)
  local copy = {}
  for i = 1, count do
    copy[i] = buffer[i]
  end
  table.sort(copy)
  return copy
end

function _M.add_metrics(instance_id, lat, queue)
  local lat_val = math.max(1.0, math.min(60000.0, lat))
  local q_val = math.max(1.0, math.min(10000.0, queue))

  if not instance_states[instance_id] then
    instance_states[instance_id] = {
      lat_buffer = {},
      lat_state = { idx = 1, count = 0 },
      q_buffer = {},
      q_state = { idx = 1, count = 0 }
    }
  end

  local s = instance_states[instance_id]
  add_to_buffer(s.lat_buffer, lat_val, MAX_LAT_SAMPLES, s.lat_state)
  add_to_buffer(s.q_buffer, q_val, MAX_LAT_SAMPLES, s.q_state)

  -- Global
  global_lat_samples[global_lat_idx] = lat_val
  global_lat_idx = global_lat_idx + 1
  if global_lat_idx > MAX_GLOBAL_SAMPLES then
    global_lat_idx = 1
  end
  if global_lat_count < MAX_GLOBAL_SAMPLES then
    global_lat_count = global_lat_count + 1
  end
end

function _M.get_snapshot(instance_id)
  local s = instance_states[instance_id]
  if not s or s.lat_state.count == 0 then
    return { p5 = 0.0, p50 = 50.0, p95 = 100.0, qP99 = 10.0 }
  end

  local sorted_lat = get_sorted_copy(s.lat_buffer, s.lat_state.count)
  local p5 = percentile(sorted_lat, 0.05)
  local p50 = percentile(sorted_lat, 0.50)
  local p95 = percentile(sorted_lat, 0.95)

  local qP99 = 10.0
  if s.q_state.count > 0 then
    local sorted_q = get_sorted_copy(s.q_buffer, s.q_state.count)
    qP99 = percentile(sorted_q, 0.99)
  end

  return { p5 = p5, p50 = p50, p95 = p95, qP99 = qP99 }
end

function _M.get_system_snapshot()
  if global_lat_count < 5 then
    return { p5 = 5.0, p75 = 50.0, p95 = 200.0 }
  end

  local sorted_lat = get_sorted_copy(global_lat_samples, global_lat_count)
  local p5 = percentile(sorted_lat, 0.05)
  local p75 = percentile(sorted_lat, 0.75)
  local p95 = percentile(sorted_lat, 0.95)

  return { p5 = p5, p75 = p75, p95 = p95 }
end

function _M.reset_all()
  global_lat_samples = {}
  global_lat_idx = 1
  global_lat_count = 0
  instance_states = {}
end

return _M
