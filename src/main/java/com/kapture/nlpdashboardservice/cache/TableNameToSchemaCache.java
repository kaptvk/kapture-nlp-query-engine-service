package com.kapture.nlpdashboardservice.cache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
@Slf4j
public class TableNameToSchemaCache {

    private final RedissonClient redissonClient;

    public static final String TABLE_NAME_TO_SCHEMA_MAP = "TABLE_NAME_TO_SCHEMA_MAP";

    public void put(String tableName, String schema) {
        try {
            RMap<String, String> map = redissonClient.getMap(TABLE_NAME_TO_SCHEMA_MAP);
            map.put(tableName, schema);
        } catch (Exception e) {
            log.error("Error in putTableSchema" + e);
        }
    }

    public String get(String tableName) {
        try {
            RMap<String, String> map = redissonClient.getMap(TABLE_NAME_TO_SCHEMA_MAP);
            return map.get(tableName);
        } catch (Exception e) {
            log.error("Error in getTableSchema" + e);
            return null;
        }
    }

    public boolean clear() {
        try {
            RMap<String, String> map = redissonClient.getMap(TABLE_NAME_TO_SCHEMA_MAP);
            map.clear();
            return true;
        } catch (Exception e) {
            log.error("Error in clearTableSchemaCache" + e);
            return false;
        }
    }

}
