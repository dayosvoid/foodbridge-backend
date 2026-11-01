package com.tesa.foodbridge.auth.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@Configuration
public class ApiUserDetailsService {
    @Bean
    @Profile("demo & !prod & !production")
    UserDetailsService demoUserDetailsService() {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        var donor1 = User.withUsername("donor1").password(encoder.encode("donor123")).roles("DONOR").build();
        var donor2 = User.withUsername("donor2").password(encoder.encode("donor123")).roles("DONOR").build();
        var recipient1 = User.withUsername("recipient1").password(encoder.encode("recipient123")).roles("RECIPIENT").build();
        var recipient2 = User.withUsername("recipient2").password(encoder.encode("recipient123")).roles("RECIPIENT").build();
        var recipient3 = User.withUsername("recipient3").password(encoder.encode("recipient123")).roles("RECIPIENT").build();
        var admin = User.withUsername("admin1").password(encoder.encode("admin123")).roles("ADMIN").build();
        return new InMemoryUserDetailsManager(donor1, donor2, recipient1, recipient2, recipient3, admin);
    }

    @Bean
    @Profile("!demo | prod | production")
    UserDetailsService nonDemoUserDetailsService() {
        // No production credential provider exists yet. Fail closed and prevent
        // Boot from creating a default user when demo authentication is disabled.
        return new InMemoryUserDetailsManager();
    }
}
