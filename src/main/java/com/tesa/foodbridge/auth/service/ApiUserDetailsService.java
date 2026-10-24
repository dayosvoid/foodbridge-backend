package com.tesa.foodbridge.auth.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class ApiUserDetailsService {
    @Bean
    UserDetailsService userDetailsService() {
        var donor = User.withUsername("donor1").password("{noop}donor123").roles("DONOR").build();
        var recipient = User.withUsername("recipient1").password("{noop}recipient123").roles("RECIPIENT").build();
        var admin = User.withUsername("admin1").password("{noop}admin123").roles("ADMIN").build();
        return new InMemoryUserDetailsManager(donor, recipient, admin);
    }
}
