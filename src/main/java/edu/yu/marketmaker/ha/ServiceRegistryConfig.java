package edu.yu.marketmaker.ha;

import org.apache.curator.framework.CuratorFramework;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServiceRegistryConfig {

    @Bean
    public ServiceRegistry serviceRegistry(CuratorFramework curator) {
        return new ServiceRegistry(curator);
    }
}