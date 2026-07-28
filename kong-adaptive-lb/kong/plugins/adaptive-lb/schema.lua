local typedefs = require "kong.db.schema.typedefs"

local target_schema = {
  type = "record",
  fields = {
    { host = { type = "string", required = true } },
    { port = { type = "integer", required = true } },
    { capacity_weight = { type = "number", default = 1.0 } },
  }
}

return {
  name = "adaptive-lb",
  fields = {
    {
      config = {
        type = "record",
        fields = {
          { targets = { type = "array", elements = target_schema, required = true } },
          
          -- Polling Configuration
          { polling_interval_ms = { type = "integer", default = 200 } },
          { weights_update_interval_ms = { type = "integer", default = 5000 } },
          
          -- EWMA Configuration
          { ewma_tau_min = { type = "number", default = 200.0 } },
          { ewma_tau_max = { type = "number", default = 2000.0 } },
          { ewma_k = { type = "number", default = 3.0 } },
          
          -- PID Configuration
          { pid_kp = { type = "number", default = 1.0 } },
          { pid_ki = { type = "number", default = 0.08 } },
          { pid_kd = { type = "number", default = 0.04 } },
          { pid_tau_d = { type = "number", default = 2.0 } },
          { pid_min_i = { type = "number", default = -0.8 } },
          { pid_max_i = { type = "number", default = 2.5 } },
          { pid_lambda = { type = "number", default = 0.8 } },
          { pid_kappa = { type = "number", default = 1.2 } },
          { pid_error_deadband = { type = "number", default = 0.08 } },
          
          -- AHP Priors
          { ahp_lat = { type = "number", default = 0.648 } },
          { ahp_queue = { type = "number", default = 0.230 } },
          { ahp_cpu = { type = "number", default = 0.122 } },
          
          -- Routing Configuration
          { routing_warmup_ms = { type = "integer", default = 5000 } },
          { routing_low_load_inflight = { type = "integer", default = 20 } },
          { routing_hard_inflight_cap = { type = "integer", default = 220 } },
          { routing_probe_interval_ms = { type = "integer", default = 3000 } },
          { routing_probe_probability = { type = "number", default = 0.005 } },
          
          -- Penalty / Stale Configuration
          { stale_soft_ms = { type = "integer", default = 1500 } },
          { stale_hard_ms = { type = "integer", default = 5000 } },
          
          -- Score Fallbacks
          { score_null = { type = "number", default = 20.0 } },
          { score_p5 = { type = "number", default = 30.0 } },
          { score_p95 = { type = "number", default = 300.0 } },
          
          -- Math
          { math_eps = { type = "number", default = 1e-9 } },
        }
      }
    }
  }
}
