package com.dealshield.auth;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AuthConfiguration {
    @Bean
    @ConditionalOnProperty(name = {"dealshield.auth.project-url", "dealshield.auth.publishable-key"})
    SupabaseAuthClient supabaseAuthClient(@Value("${dealshield.auth.project-url}") String url, @Value("${dealshield.auth.publishable-key}") String key) {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return new SupabaseAuthClient(url, key, RestClient.builder().requestFactory(factory));
    }
}
