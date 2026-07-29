return {
  ["/alb-metrics"] = {
    methods = {
      GET = function(self, db, helpers)
        local scores = ngx.shared.alb_scores
        local inflight = ngx.shared.alb_inflight
        
        local out = {}
        table.insert(out, "# HELP alb_mcdm_weight Trọng số MCDM động")
        table.insert(out, "# TYPE alb_mcdm_weight gauge")
        
        local alpha = scores:get("__mcdm_alpha__") or 0.648
        local beta = scores:get("__mcdm_beta__") or 0.230
        local gamma = scores:get("__mcdm_gamma__") or 0.122
        local mode = scores:get("__mcdm_mode__") or 0.0
        
        table.insert(out, string.format('alb_mcdm_weight{criterion="latency"} %f', alpha))
        table.insert(out, string.format('alb_mcdm_weight{criterion="queue"} %f', beta))
        table.insert(out, string.format('alb_mcdm_weight{criterion="cpu"} %f', gamma))
        
        table.insert(out, "# HELP alb_mcdm_update_mode Chế độ cập nhật MCDM (0=frozen, 1=dynamic)")
        table.insert(out, "# TYPE alb_mcdm_update_mode gauge")
        table.insert(out, string.format('alb_mcdm_update_mode %f', mode))
        
        table.insert(out, "# HELP alb_mcdm_recent_actual_rps RPS thực tế gần đây")
        table.insert(out, "# TYPE alb_mcdm_recent_actual_rps gauge")
        local rps = scores:get("__global_rps__") or 0.0
        table.insert(out, string.format('alb_mcdm_recent_actual_rps %f', rps))
        
        table.insert(out, "# HELP alb_routing_cost Routing metrics per instance")
        table.insert(out, "# TYPE alb_routing_cost gauge")
        
        -- Tìm danh sách instance keys
        local keys = scores:get_keys(1024)
        for _, k in ipairs(keys) do
          -- Lọc ra các key là instance (VD: 172.30.35.37:8081)
          if string.find(k, ":808") and not string.find(k, "_") then
            local final = scores:get(k) or 0
            local nL = scores:get(k .. "_nl") or 0
            local nQ = scores:get(k .. "_nq") or 0
            local nC = scores:get(k .. "_nc") or 0
            local base = scores:get(k .. "_base") or 0
            local pid_pen = scores:get(k .. "_pid") or 0
            local ewm_lat = scores:get(k .. "_lat") or 0
            local cur_inf = inflight:get(k) or 0
            
            table.insert(out, string.format('alb_routing_cost{instance="%s", metric="final_score"} %f', k, final))
            table.insert(out, string.format('alb_routing_cost{instance="%s", metric="nL"} %f', k, nL))
            table.insert(out, string.format('alb_routing_cost{instance="%s", metric="nQ"} %f', k, nQ))
            table.insert(out, string.format('alb_routing_cost{instance="%s", metric="nC"} %f', k, nC))
            table.insert(out, string.format('alb_routing_cost{instance="%s", metric="base_score"} %f', k, base))
            table.insert(out, string.format('alb_routing_cost{instance="%s", metric="pid_penalty"} %f', k, pid_pen))
            table.insert(out, string.format('alb_routing_cost{instance="%s", metric="ewma_lat"} %f', k, ewm_lat))
            table.insert(out, string.format('alb_routing_cost{instance="%s", metric="inflight"} %f', k, cur_inf))
          end
        end
        
        return kong.response.exit(200, table.concat(out, "\n") .. "\n", {["Content-Type"] = "text/plain; version=0.0.4"})
      end
    }
  }
}
