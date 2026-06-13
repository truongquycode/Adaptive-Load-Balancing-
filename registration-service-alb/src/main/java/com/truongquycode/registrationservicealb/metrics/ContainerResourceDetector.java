package com.truongquycode.registrationservicealb.metrics;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class ContainerResourceDetector {

    private volatile Double cachedCpuCapacity;

    public double getCpuCapacityCores() {
        Double cached = cachedCpuCapacity;
        if (cached != null) {
            return cached;
        }

        double detected = detectCpuCapacityCores();
        cachedCpuCapacity = detected;
        return detected;
    }

    private double detectCpuCapacityCores() {
        // cgroup v2: /sys/fs/cgroup/cpu.max
        Double cgroupV2 = readCgroupV2CpuMax();
        if (cgroupV2 != null && cgroupV2 > 0) {
            return cgroupV2;
        }

        // cgroup v1: cpu.cfs_quota_us / cpu.cfs_period_us
        Double cgroupV1 = readCgroupV1CpuQuota();
        if (cgroupV1 != null && cgroupV1 > 0) {
            return cgroupV1;
        }

        // Fallback: JVM container-aware available processors.
        return Math.max(1.0, Runtime.getRuntime().availableProcessors());
    }

    private Double readCgroupV2CpuMax() {
        try {
            Path path = Path.of("/sys/fs/cgroup/cpu.max");
            if (!Files.exists(path)) {
                return null;
            }

            String content = Files.readString(path).trim();
            String[] parts = content.split("\\s+");

            if (parts.length < 2) {
                return null;
            }

            // "max 100000" nghĩa là không bị giới hạn CPU.
            if ("max".equals(parts[0])) {
                return null;
            }

            long quota = Long.parseLong(parts[0]);
            long period = Long.parseLong(parts[1]);

            if (quota <= 0 || period <= 0) {
                return null;
            }

            return (double) quota / period;
        } catch (Exception e) {
            return null;
        }
    }

    private Double readCgroupV1CpuQuota() {
        try {
            Path quotaPath = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us");
            Path periodPath = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_period_us");

            if (!Files.exists(quotaPath) || !Files.exists(periodPath)) {
                return null;
            }

            long quota = Long.parseLong(Files.readString(quotaPath).trim());
            long period = Long.parseLong(Files.readString(periodPath).trim());

            // quota = -1 nghĩa là không giới hạn CPU.
            if (quota <= 0 || period <= 0) {
                return null;
            }

            return (double) quota / period;
        } catch (Exception e) {
            return null;
        }
    }
}