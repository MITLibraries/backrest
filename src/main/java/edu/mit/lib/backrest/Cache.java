/**
 * Copyright (C) 2016 MIT Libraries
 * Licensed under: http://www.apache.org/licenses/LICENSE-2.0
 */
package edu.mit.lib.backrest;

import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

import javax.xml.bind.annotation.XmlRootElement;

import com.google.common.base.Splitter;
import com.google.common.cache.CacheBuilder;

import static com.google.common.base.Strings.*;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

import org.slf4j.Logger;

import spark.Request;
import spark.Response;

/**
 * Cache provides response document caching services for backrest,
 * abstracting the implementation details. Currently back-ends are:
 * (1) in-process/in-memory Guava cache
 * (2) networked/in-memory Redis cache
 *
 * @author richardrodgers
 */
public class Cache {

    private static com.google.common.cache.Cache<String, String> localCache;
    private static long localSize = 0L;
    private static JedisPool pool;
    private static int jedisExpire = -1;

    static void setCache(String svcType) {
        Iterator<String> policies = Splitter.on(":").split(System.getenv("BACKREST_CACHE")).iterator();
        switch (svcType) {
            case "local": configLocal(policies); break;
            case "redis": configRedis(policies); break;
            default: Backrest.logger.info("Unknown cache type: {}", svcType); break;
        }
    }

    private static void configLocal(Iterator<String> policies) {
        CacheBuilder builder = CacheBuilder.newBuilder();
        String maxEntries = policies.next();
        if (! isNullOrEmpty(maxEntries)) {
            builder = builder.maximumSize(Long.valueOf(maxEntries));
        }
        String retain = policies.next();
        if (! isNullOrEmpty(retain)) {
            int len = retain.length();
            TimeUnit tu = getUnit(retain.charAt(len-1));
            builder = builder.expireAfterAccess(Long.valueOf(retain.substring(0, len-1)), tu);
        }
        localCache = builder.build();
    }

    private static TimeUnit getUnit(char c) {
        switch (c) {
            case 'd': return TimeUnit.DAYS;
            case 'h': return TimeUnit.HOURS;
            case 'm': return TimeUnit.MINUTES;
            case 's': return TimeUnit.SECONDS;
            default: Backrest.logger.info("Unknown time unit: {}", c); return null;
        }
    }

    private static int scaleToSecs(int expire, char c) {
        switch (c) {
            case 'd': return expire * 86400;
            case 'h': return expire * 3600;
            case 'm': return expire * 60;
            case 's': return expire;
            default: Backrest.logger.info("Unknown time unit: {}", c); return -1;
        }
    }

    private static void configRedis(Iterator<String> policies) {
        pool = new JedisPool(new JedisPoolConfig(), System.getenv("BACKREST_REDIS_HOST"));
        // max entries not directly configurable in redis - only total space
        // so use this value * avg doc size of 2k to set maximum memory
        String maxEntries = policies.next();
        if (! isNullOrEmpty(maxEntries)) {
            String memMax = String.valueOf((Long.valueOf(maxEntries) * 2) / 1000) + "mb";
            try (Jedis jedis = pool.getResource()) {
                jedis.configSet("maxmemory", memMax);
            }
        }
        String retain = policies.next();
        if (! isNullOrEmpty(retain)) {
            int len = retain.length();
            int expire = Integer.valueOf(retain.substring(0, len-1));
            jedisExpire = scaleToSecs(expire, retain.charAt(len-1));
            try (Jedis jedis = pool.getResource()) {
                jedis.configSet("maxmemory-policy", "volatile-lru");
            }
        }
    }

    static void shutdownCache() {
        if (pool != null) pool.destroy();
    }

    static void getIfCachable(Request req) {
        if (cacheable(req)) {
            String fromCache = get(req);
            req.attribute("cacheCtl", (fromCache == null) ? "miss" : fromCache);
        } else {
            req.attribute("cacheCtl", "none");
        }
    }

    static void remember(Request req, String response) {
        if ("miss".equals((String)req.attribute("cacheCtl"))) {
            String key = cacheKey(req);
            if (localCache != null) {
                localCache.put(key, response);
                localSize += response.length();
                //logger.info("wrote to cache: {}", res.body());
            } else if (pool != null ){
                try (Jedis jedis = pool.getResource()) {
                    if (jedisExpire != -1) {
                        jedis.setex(key, jedisExpire, response);
                    } else {
                        jedis.set(key, response);
                    }
                }
            }
        }
    }

    static boolean inCache(Request req) {
        String status = (String)req.attribute("cacheCtl");
        return status != null && ! ("miss".equals(status) || "none".equals(status));
    }

    static String fromCache(Request req, Response res) {
        res.type(Backrest.responseContentType(req));
        return (String)req.attribute("cacheCtl");
    }

    static Status cacheStatus() {
        if (localCache != null) {
            return new Status(Math.toIntExact(localCache.size()), localSize);
        } else if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                String memStats = jedis.info("Memory");
                String usedStr = new Scanner(memStats).findWithinHorizon("used_memory:(\\d+)", 0);
                long usedMem = Long.valueOf(usedStr.substring(usedStr.indexOf(":") + 1));
                return new Status(Math.toIntExact(jedis.dbSize()), usedMem);
            }
        }
        return null;
    }

    static void cacheControl(String command) {
        switch (command) {
            case "flush": flush(); break;
            default: Backrest.logger.info("Unknown cache command: {}", command); break;
        }
    }

    static boolean cacheActive() {
        return localCache != null || pool != null;
    }

    private static String cacheKey(Request req) {
        return req.url() + req.queryString() + Backrest.responseContentType(req);
    }

    private static boolean cacheable(Request req) {
        if (cacheActive()) {
            String path = req.pathInfo();
            return (path.contains("items") || path.contains("collections") || path.contains("communities") ||
                    path.contains("bitstreams") || path.contains("handle")) && ! path.contains("retrieve");
        }
        return false;
    }

    private static String get(Request req) {
        // key is request URL + response content type
        String key = cacheKey(req);
        if (localCache != null) {
            return localCache.getIfPresent(key);
        } else if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                String value = null;
                if (jedisExpire != -1) {
                    // have to do as a transaction
                    Transaction tx = jedis.multi();
                    redis.clients.jedis.Response<String> resp = tx.get(key);
                    tx.expire(key, jedisExpire);
                    tx.exec();
                    value = resp.get();
                } else {
                    value = jedis.get(key);
                }
                return value;
            }
        }
        return null;
    }

    private static void flush() {
        if (localCache != null) {
            localCache.invalidateAll();
            localSize = 0L;
        } else if (pool != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.flushDB();
            }
        }
    }

    @XmlRootElement(name="cacheStatus")
    static class Status {

        public int entries;
        public long size;

        Status() {}

        Status(int entries, long size) {
            this.entries = entries;
            this.size = size;
        }
    }
}
