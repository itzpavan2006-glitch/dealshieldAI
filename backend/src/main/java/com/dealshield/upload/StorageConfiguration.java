package com.dealshield.upload;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class StorageConfiguration {
 @Bean @ConditionalOnProperty(name={"dealshield.auth.project-url","dealshield.auth.publishable-key"})
 SupabaseStorageClient storage(@Value("${dealshield.auth.project-url}")String url,@Value("${dealshield.auth.publishable-key}")String key){var http=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();var f=new JdkClientHttpRequestFactory(http);f.setReadTimeout(Duration.ofSeconds(20));return new SupabaseStorageClient(url,key,RestClient.builder().requestFactory(f));}
}
