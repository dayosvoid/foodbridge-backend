package com.tesa.foodbridge.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import static org.junit.jupiter.api.Assertions.*;

class DemoAuthenticationConfigTest {
    private ApplicationContextRunner context(String... profiles) {
        return new ApplicationContextRunner().withUserConfiguration(ApiUserDetailsService.class)
                .withInitializer(c -> c.getEnvironment().setActiveProfiles(profiles));
    }
    @Test void demoDonorsUseEncodedPasswords() {
        context("demo").run(c -> {
            var users=c.getBean(UserDetailsService.class);
            var encoder=PasswordEncoderFactories.createDelegatingPasswordEncoder();
            for(String name:java.util.List.of("donor1","donor2")) {
                var user=users.loadUserByUsername(name);
                assertTrue(user.getPassword().startsWith("{bcrypt}"));
                assertTrue(encoder.matches("donor123",user.getPassword()));
                assertFalse(encoder.matches("wrong-password",user.getPassword()));
                assertEquals("ROLE_DONOR",user.getAuthorities().iterator().next().getAuthority());
            }
        });
    }
    @Test void demoCredentialsAreUnavailableOutsideDemoAndInProduction() {
        for(String[] profiles:new String[][]{{},{"prod"},{"production"},{"demo","prod"},{"demo","production"}}) {
            context(profiles).run(c -> {
                var users=c.getBean(UserDetailsService.class);
                for(String name:java.util.List.of("donor1","donor2","recipient1","admin1"))
                    assertThrows(UsernameNotFoundException.class,()->users.loadUserByUsername(name));
            });
        }
    }
}
