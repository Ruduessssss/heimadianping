package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private String name ;

    private StringRedisTemplate stringRedisTemplate;

    private static final String KEY_PREFIX = "lock:";

    //每个jvm的threadid都是自增的,如果多线程操控同一个用户购买,有可能导致threadid相等
    //加上UUID是为了避免两个jvm的线程id对上导致误删锁
    //UUID保证每个JVM唯一,getid保证每个JVM内部的线程id唯一
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeout) {
        String key = KEY_PREFIX+name;

        String id = ID_PREFIX + Thread.currentThread().getId();

        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, id, timeout, TimeUnit.SECONDS);

        return Boolean.TRUE.equals(success);
    }

    @Override
    public void releaseLock() {
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX + Thread.currentThread().getId());
    }


    //如果线程1在获取锁后阻塞时间超过ttl,那么它有可能会在锁过期后(线程2获取到锁时)进行删除,导致线程3获取到锁,2,3并发
    //@Override
//    public void releaseLock() {
//
//        String threadId = ID_PREFIX+Thread.currentThread().getId();
//
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
//
//        //判断锁是否还是自己的
//        if(threadId.equals(id))
//        {
//            //是的就删除
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//        //不是就不删除
//    }
}
