package com.midnight.deal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

@Configuration
public class RedisConfig {
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }
    @Bean
    public RedisScript<Long> reserveScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/reserve.lua")));
        s.setResultType(Long.class);
        return s;
    }
    @Bean
    public RedisScript<Long> releaseScript() {
        DefaultRedisScript<Long> s = new DefaultRedisScript<>();
        s.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/release.lua")));
        s.setResultType(Long.class);
        return s;
    }
}
