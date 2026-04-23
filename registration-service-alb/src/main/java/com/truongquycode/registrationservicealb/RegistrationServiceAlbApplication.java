package com.truongquycode.registrationservicealb;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

// Chỉ exclude Database vì module này không dùng DB
@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
@EnableDiscoveryClient
public class RegistrationServiceAlbApplication {
    public static void main(String[] args) {
        SpringApplication.run(RegistrationServiceAlbApplication.class, args);
    }
}