import xml.etree.ElementTree as ET

def create_cell(root, cell_id, value, style, parent="1", vertex="1", edge=None, source=None, target=None, geom=None):
    attribs = {
        "id": cell_id,
        "value": value,
        "style": style,
        "parent": parent,
    }
    if vertex:
        attribs["vertex"] = "1"
    if edge:
        attribs["edge"] = "1"
    if source:
        attribs["source"] = source
    if target:
        attribs["target"] = target
        
    cell = ET.SubElement(root, "mxCell", attribs)
    if geom is not None:
        geom_elem = ET.SubElement(cell, "mxGeometry", geom)
        if edge:
            geom_elem.set("relative", "1")
            geom_elem.set("as", "geometry")
        else:
            geom_elem.set("as", "geometry")
            
    return cell

def main():
    mxfile = ET.Element("mxfile", version="21.6.8")
    diagram = ET.SubElement(mxfile, "diagram", id="alb_arch", name="ALB System Architecture")
    mxGraphModel = ET.SubElement(diagram, "mxGraphModel", dx="1422", dy="800", grid="1", gridSize="10", guides="1", tooltips="1", connect="1", arrows="1", fold="1", page="1", pageScale="1", pageWidth="1440", pageHeight="810", math="0", shadow="1")
    root = ET.SubElement(mxGraphModel, "root")
    
    ET.SubElement(root, "mxCell", id="0")
    ET.SubElement(root, "mxCell", id="1", parent="0")

    # Styles
    zone_base = "rounded=1;whiteSpace=wrap;html=1;strokeWidth=2;align=center;verticalAlign=top;spacingTop=15;fontFamily=Helvetica;fontSize=18;fontStyle=1;shadow=1;arcSize=2;"
    z1_style = zone_base + "fillColor=#F8FAFC;strokeColor=#CBD5E1;fontColor=#334155;"
    z2_style = zone_base + "fillColor=#F0FDF4;strokeColor=#86EFAC;fontColor=#166534;"
    z3_style = zone_base + "fillColor=#EFF6FF;strokeColor=#93C5FD;fontColor=#1E40AF;"
    z4_style = zone_base + "fillColor=#F5F3FF;strokeColor=#C4B5FD;fontColor=#5B21B6;"

    client_style = "rounded=1;whiteSpace=wrap;html=1;fillColor=#FFFFFF;strokeColor=#475569;strokeWidth=2;fontFamily=Helvetica;fontSize=16;fontStyle=1;shadow=1;"
    gw_style = "rounded=1;whiteSpace=wrap;html=1;fillColor=#2B6CB0;strokeColor=#1A365D;strokeWidth=2;fontColor=#FFFFFF;fontFamily=Helvetica;fontSize=16;fontStyle=1;shadow=1;"
    eureka_style = "rounded=1;whiteSpace=wrap;html=1;fillColor=#805AD5;strokeColor=#44337A;strokeWidth=2;fontColor=#FFFFFF;fontFamily=Helvetica;fontSize=16;fontStyle=1;shadow=1;"
    
    be_base = "rounded=1;whiteSpace=wrap;html=1;strokeWidth=2;fontColor=#FFFFFF;fontFamily=Helvetica;fontSize=14;shadow=1;"
    be1_style = be_base + "fillColor=#2F855A;strokeColor=#1C4532;"
    be2_style = be_base + "fillColor=#D69E2E;strokeColor=#744210;"
    be3_style = be_base + "fillColor=#C53030;strokeColor=#742A2A;"
    
    obs_style = "rounded=1;whiteSpace=wrap;html=1;fillColor=#ED8936;strokeColor=#7B341E;strokeWidth=2;fontColor=#FFFFFF;fontFamily=Helvetica;fontSize=16;fontStyle=1;shadow=1;"
    cadvisor_style = obs_style
    
    callout_style = "shape=note;whiteSpace=wrap;html=1;backgroundOutline=1;darkOpacity=0.05;fillColor=#FEFCBF;strokeColor=#B7791F;fontFamily=Helvetica;fontSize=12;align=left;spacing=10;shadow=1;"
    docker_style = "shape=module;whiteSpace=wrap;html=1;fillColor=#EBF8FF;strokeColor=#2B6CB0;strokeWidth=2;fontFamily=Helvetica;fontSize=14;align=left;spacingLeft=10;spacingTop=10;shadow=1;"
    annot_style = "text;html=1;strokeColor=none;fillColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;fontFamily=Helvetica;fontSize=14;fontStyle=2;fontColor=#4A5568;"
    
    # Arrows
    req_edge = "html=1;strokeWidth=3;strokeColor=#2B6CB0;edgeStyle=orthogonalEdgeStyle;rounded=1;curved=1;endArrow=block;endFill=1;"
    disc_edge = "html=1;strokeWidth=2;strokeColor=#805AD5;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=1;curved=0;endArrow=classic;endFill=1;"
    metric_edge = "html=1;strokeWidth=2;strokeColor=#DD6B20;dashed=1;dashPattern=1 2;edgeStyle=orthogonalEdgeStyle;rounded=1;curved=0;endArrow=classic;endFill=1;"
    legend_arrow_req = "html=1;strokeWidth=3;strokeColor=#2B6CB0;endArrow=block;endFill=1;edgeStyle=orthogonalEdgeStyle;"
    legend_arrow_disc = "html=1;strokeWidth=2;strokeColor=#805AD5;dashed=1;endArrow=classic;endFill=1;edgeStyle=orthogonalEdgeStyle;"
    legend_arrow_metric = "html=1;strokeWidth=2;strokeColor=#DD6B20;dashed=1;dashPattern=1 2;endArrow=classic;endFill=1;edgeStyle=orthogonalEdgeStyle;"

    # Create Zones
    create_cell(root, "z1", "1. Load Generation", z1_style, "1", "1", None, None, None, {"x": "20", "y": "20", "width": "220", "height": "760"})
    create_cell(root, "z2", "2. Application Layer", z2_style, "1", "1", None, None, None, {"x": "260", "y": "20", "width": "540", "height": "760"})
    create_cell(root, "z3", "3. Observability Layer", z3_style, "1", "1", None, None, None, {"x": "820", "y": "20", "width": "300", "height": "760"})
    create_cell(root, "z4", "4. Deployment / Runtime Context", z4_style, "1", "1", None, None, None, {"x": "1140", "y": "20", "width": "280", "height": "760"})

    # Zone 1 contents
    create_cell(root, "jmeter", "<b>JMeter / Client</b><br/><br/><i>Generates benchmark<br/>traffic to the Gateway</i>", client_style, "1", "1", None, None, None, {"x": "50", "y": "340", "width": "160", "height": "90"})
    
    # Legend
    leg_box = "rounded=0;whiteSpace=wrap;html=1;fillColor=#FFFFFF;strokeColor=#CBD5E1;align=left;spacingLeft=10;spacingTop=5;fontFamily=Helvetica;fontSize=12;"
    create_cell(root, "legend", "<b>Chú giải (Legend)</b>", leg_box, "1", "1", None, None, None, {"x": "40", "y": "630", "width": "180", "height": "120"})
    create_cell(root, "leg_r_arr", "", legend_arrow_req, "1", None, "1", None, None, {"x": "50", "y": "670"}).append(ET.fromstring('<mxGeometry><mxPoint x="50" y="670" as="sourcePoint"/><mxPoint x="90" y="670" as="targetPoint"/></mxGeometry>'))
    create_cell(root, "leg_r_txt", "Request flow", "text;html=1;align=left;verticalAlign=middle;fontSize=12;fontFamily=Helvetica;", "1", "1", None, None, None, {"x": "95", "y": "660", "width": "100", "height": "20"})
    create_cell(root, "leg_d_arr", "", legend_arrow_disc, "1", None, "1", None, None, {"x": "50", "y": "700"}).append(ET.fromstring('<mxGeometry><mxPoint x="50" y="700" as="sourcePoint"/><mxPoint x="90" y="700" as="targetPoint"/></mxGeometry>'))
    create_cell(root, "leg_d_txt", "Service discovery", "text;html=1;align=left;verticalAlign=middle;fontSize=12;fontFamily=Helvetica;", "1", "1", None, None, None, {"x": "95", "y": "690", "width": "100", "height": "20"})
    create_cell(root, "leg_m_arr", "", legend_arrow_metric, "1", None, "1", None, None, {"x": "50", "y": "730"}).append(ET.fromstring('<mxGeometry><mxPoint x="50" y="730" as="sourcePoint"/><mxPoint x="90" y="730" as="targetPoint"/></mxGeometry>'))
    create_cell(root, "leg_m_txt", "Metrics scraping", "text;html=1;align=left;verticalAlign=middle;fontSize=12;fontFamily=Helvetica;", "1", "1", None, None, None, {"x": "95", "y": "720", "width": "100", "height": "20"})

    # Zone 2 contents
    gw_label = "<b>API Gateway ALB</b><br/>(Port: 8080)<br/><hr/><div style='font-size:12px;font-weight:normal;'>- Spring Cloud Gateway<br/>- Spring Cloud LoadBalancer<br/>- Custom Strategy Selection</div>"
    create_cell(root, "gw", gw_label, gw_style, "1", "1", None, None, None, {"x": "290", "y": "280", "width": "200", "height": "110"})
    gw_callout = "<b>Supported Strategies:</b><br/>• Adaptive (Custom)<br/>• Round Robin<br/>• Random<br/>• Least Connections"
    create_cell(root, "gw_callout", gw_callout, callout_style, "1", "1", None, None, None, {"x": "305", "y": "410", "width": "170", "height": "90"})
    route_label_style = "text;html=1;strokeColor=#4299E1;fillColor=#EBF8FF;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=1;fontFamily=Helvetica;fontSize=12;fontStyle=1;fontColor=#2B6CB0;shadow=1;"
    create_cell(root, "gw_route", "Route: /api/** → lb://REGISTRATION-SERVICE-ALB", route_label_style, "1", "1", None, None, None, {"x": "290", "y": "230", "width": "310", "height": "30"})
    create_cell(root, "annot_gw", "Điểm quyết định định tuyến duy nhất của hệ thống", annot_style, "1", "1", None, None, None, {"x": "290", "y": "180", "width": "200", "height": "40"})
    create_cell(root, "annot_be", "Các backend có năng lực không đồng nhất", annot_style, "1", "1", None, None, None, {"x": "550", "y": "190", "width": "210", "height": "40"})
    eureka_lbl = "<b>Eureka Server</b><br/>(Port: 8761)<br/><i>Service Discovery</i>"
    create_cell(root, "eureka", eureka_lbl, eureka_style, "1", "1", None, None, None, {"x": "430", "y": "70", "width": "180", "height": "70"})

    be_label = "<b>REGISTRATION-SERVICE-ALB</b><br/><div style='font-size:12px;font-weight:normal;margin-top:4px;'>Port: {port}<br/>Capacity: {cpu} CPU, {mem}</div><div style='font-size:11px;margin-top:6px;background-color:rgba(0,0,0,0.2);padding:2px;border-radius:4px;'>{desc}</div>"
    create_cell(root, "be1", be_label.format(port="8081", cpu="2.0", mem="768 MB", desc="Strongest backend"), be1_style, "1", "1", None, None, None, {"x": "550", "y": "240", "width": "210", "height": "90"})
    create_cell(root, "be2", be_label.format(port="8082", cpu="1.5", mem="512 MB", desc="Medium backend"), be2_style, "1", "1", None, None, None, {"x": "550", "y": "360", "width": "210", "height": "90"})
    be3_target = "<b>Weakest backend / Chaos target</b><br/><i>(instance yếu nhất)</i>"
    create_cell(root, "be3", be_label.format(port="8083", cpu="1.0", mem="384 MB", desc=be3_target), be3_style, "1", "1", None, None, None, {"x": "550", "y": "480", "width": "210", "height": "100"})

    # Zone 3 contents
    create_cell(root, "annot_obs", "Theo dõi số liệu phục vụ benchmark<br/>và phân tích thuật toán", annot_style, "1", "1", None, None, None, {"x": "840", "y": "80", "width": "260", "height": "40"})
    create_cell(root, "grafana", "<b>Grafana</b><br/><i>Dashboards & Viz</i>", obs_style.replace("#ED8936", "#DD6B20"), "1", "1", None, None, None, {"x": "880", "y": "140", "width": "160", "height": "70"})
    create_cell(root, "prometheus", "<b>Prometheus</b><br/><i>Metrics Server</i>", obs_style, "1", "1", None, None, None, {"x": "880", "y": "300", "width": "160", "height": "70"})
    create_cell(root, "cadvisor", "<b>cAdvisor</b><br/><i>Container Metrics</i>", cadvisor_style, "1", "1", None, None, None, {"x": "880", "y": "500", "width": "160", "height": "70"})
    dash_callout = "<b>Key Dashboard Groups:</b><br/>• Gateway latency percentiles<br/>• Gateway throughput & errors<br/>• Routing selection & reason<br/>• EWMA latency<br/>• Queue depth / inflight<br/>• Routing cost (Final & Absolute)<br/>• CPU / Memory usage"
    create_cell(root, "dash_callout", dash_callout, callout_style, "1", "1", None, None, None, {"x": "850", "y": "600", "width": "240", "height": "140"})

    # Zone 4 contents
    create_cell(root, "docker_comp", "<b>Docker Compose Environment</b><br/><br/><i>Microservice Testbed</i>", docker_style, "1", "1", None, None, None, {"x": "1160", "y": "100", "width": "240", "height": "180"})
    net_box = "rounded=1;whiteSpace=wrap;html=1;fillColor=#E2E8F0;strokeColor=#64748B;fontFamily=Helvetica;fontSize=14;fontStyle=1;"
    create_cell(root, "network", "<b>Bridge Network</b><br/>(alb-network)", net_box, "1", "1", None, None, None, {"x": "1190", "y": "180", "width": "180", "height": "60"})
    docker_details = "All components run in isolated containers with explicitly configured CPU and Memory quotas to simulate heterogeneous deployment."
    create_cell(root, "docker_desc", docker_details, "text;html=1;align=left;verticalAlign=top;whiteSpace=wrap;fontFamily=Helvetica;fontSize=13;fontColor=#475569;", "1", "1", None, None, None, {"x": "1170", "y": "300", "width": "220", "height": "100"})

    # Edges
    e1 = create_cell(root, "e_req1", "", req_edge, "1", None, "1", "jmeter", "gw")
    e1.find("mxGeometry").set("entryX", "0")
    e1.find("mxGeometry").set("entryY", "0.5")
    e1.find("mxGeometry").set("exitX", "1")
    e1.find("mxGeometry").set("exitY", "0.5")

    for be in ["be1", "be2", "be3"]:
        e = create_cell(root, f"e_req_{be}", "", req_edge, "1", None, "1", "gw", be)
        e.find("mxGeometry").set("exitX", "1")
        e.find("mxGeometry").set("exitY", "0.5")
        e.find("mxGeometry").set("entryX", "0")
        e.find("mxGeometry").set("entryY", "0.5")

    e_disc_gw = create_cell(root, "e_disc_gw", "", disc_edge, "1", None, "1", "gw", "eureka")
    e_disc_gw.find("mxGeometry").set("exitX", "0.5")
    e_disc_gw.find("mxGeometry").set("exitY", "0")
    e_disc_gw.find("mxGeometry").set("entryX", "0")
    e_disc_gw.find("mxGeometry").set("entryY", "0.5")

    for be in ["be1", "be2", "be3"]:
        e = create_cell(root, f"e_disc_{be}", "", disc_edge, "1", None, "1", be, "eureka")
        e.find("mxGeometry").set("exitX", "0.5")
        e.find("mxGeometry").set("exitY", "0")
        e.find("mxGeometry").set("entryX", "0.75")
        e.find("mxGeometry").set("entryY", "1")

    e_m_gw = create_cell(root, "e_m_gw", "", metric_edge, "1", None, "1", "prometheus", "gw")
    e_m_gw.find("mxGeometry").set("exitX", "0")
    e_m_gw.find("mxGeometry").set("exitY", "0.25")
    e_m_gw.find("mxGeometry").set("entryX", "0.75")
    e_m_gw.find("mxGeometry").set("entryY", "0")

    for be in ["be1", "be2", "be3"]:
        e = create_cell(root, f"e_m_{be}", "", metric_edge, "1", None, "1", "prometheus", be)
        e.find("mxGeometry").set("exitX", "0")
        e.find("mxGeometry").set("exitY", "0.5")
        e.find("mxGeometry").set("entryX", "1")
        e.find("mxGeometry").set("entryY", "0.5")

    e_m_cadv = create_cell(root, "e_m_cadv", "", metric_edge, "1", None, "1", "prometheus", "cadvisor")
    e_m_cadv.find("mxGeometry").set("exitX", "0.5")
    e_m_cadv.find("mxGeometry").set("exitY", "1")
    e_m_cadv.find("mxGeometry").set("entryX", "0.5")
    e_m_cadv.find("mxGeometry").set("entryY", "0")
    
    e_g_p = create_cell(root, "e_g_p", "", metric_edge.replace("dashed=1", "dashed=0"), "1", None, "1", "grafana", "prometheus")
    e_g_p.find("mxGeometry").set("exitX", "0.5")
    e_g_p.find("mxGeometry").set("exitY", "1")
    e_g_p.find("mxGeometry").set("entryX", "0.5")
    e_g_p.find("mxGeometry").set("entryY", "0")
    
    # Needs to be attached to the edge. The simplest way in raw XML is to set parent to the edge ID.
    lbl_geom = '<mxGeometry x="-0.2" y="10" relative="1" as="geometry"><mxPoint as="offset"/></mxGeometry>'
    lbl = create_cell(root, "g_p_lbl", "Reads", "text;html=1;align=center;verticalAlign=middle;fontSize=12;fontColor=#DD6B20;", "e_g_p", "1", None, None, None, None)
    lbl.append(ET.fromstring(lbl_geom))

    import os
    os.makedirs("docs/diagrams", exist_ok=True)
    
    tree = ET.ElementTree(mxfile)
    if hasattr(ET, 'indent'):
        ET.indent(tree, space="  ", level=0)
    tree.write("docs/diagrams/alb-system-architecture.drawio", encoding="utf-8", xml_declaration=False)
    print("Created docs/diagrams/alb-system-architecture.drawio")

if __name__ == "__main__":
    main()
