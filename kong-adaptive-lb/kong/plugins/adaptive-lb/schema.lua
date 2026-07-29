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
          
          -- Routing Configuration (Adaptive v4)
          { routing_warmup_ms = { type = "integer", default = 5000 } },
          { routing_min_expected_inflight = { type = "number", default = 3.0 } },
          { routing_low_load_inflight = { type = "integer", default = 20 } },
          { routing_low_load_health_spread = { type = "number", default = 0.12 } },
          { routing_low_load_load_spread = { type = "number", default = 0.25 } },

          { routing_min_health_weight = { type = "number", default = 0.35 } },
          { routing_max_health_weight = { type = "number", default = 0.80 } },
          { routing_min_routing_norm_range = { type = "number", default = 0.12 } },
          { routing_weight_ema_alpha = { type = "number", default = 0.18 } },
          { routing_dominant_threshold = { type = "number", default = 0.70 } },

          { stale_soft_ms = { type = "integer", default = 800 } },
          { stale_hard_ms = { type = "integer", default = 2000 } },
          { routing_stale_penalty_weight = { type = "number", default = 0.30 } },

          { routing_unhealthy_score_cutoff = { type = "number", default = 2.0 } },
          { routing_hard_inflight_cap = { type = "integer", default = 180 } },
          { routing_hard_inflight_cap_min = { type = "integer", default = 40 } },
          { routing_capacity_cap_factor_min = { type = "number", default = 0.70 } },
          { routing_capacity_cap_factor_max = { type = "number", default = 1.50 } },

          { routing_probe_interval_ms = { type = "integer", default = 2000 } },
          { routing_probe_probability = { type = "number", default = 0.02 } },
          { routing_probe_max_total_inflight_ratio = { type = "number", default = 0.70 } },
          { routing_probe_max_load_raw = { type = "number", default = 1.10 } },
          { routing_probe_max_absolute_latency_cost = { type = "number", default = 0.80 } },
          { routing_probe_max_final_cost = { type = "number", default = 1.50 } },

          { routing_overload_penalty_weight = { type = "number", default = 0.30 } },
          { routing_cap_pressure_penalty_weight = { type = "number", default = 0.20 } },
          { routing_absolute_health_penalty_weight = { type = "number", default = 0.12 } },
          { routing_absolute_latency_penalty_weight = { type = "number", default = 0.12 } },

          { routing_absolute_latency_target_ms = { type = "number", default = 300.0 } },
          { routing_absolute_latency_critical_ms = { type = "number", default = 1500.0 } },

          { routing_overload_start_ratio = { type = "number", default = 0.95 } },
          { routing_overload_full_ratio = { type = "number", default = 1.40 } },
          { routing_cap_pressure_start_ratio = { type = "number", default = 0.70 } },
          { routing_cap_pressure_full_ratio = { type = "number", default = 1.00 } },
          { routing_absolute_health_start = { type = "number", default = 0.75 } },
          { routing_absolute_health_full = { type = "number", default = 1.50 } },
          
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
