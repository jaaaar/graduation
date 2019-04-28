package top.ezttf.graduation.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * @author yuwen
 * @date 2019/4/27
 */
@Slf4j
@Component
public class DeviceIndex implements IIndexAware<String, Long> {

    private ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<>();

    @Override
    public Long get(String key) {
        return map.get(key);
    }

    @Override
    public void add(String key, Long value) {
        log.info("DeviceIndex, before add the key set is {}", map.keySet());
        map.put(key, value);
        log.info("DeviceIndex, after add the key set is {}", map.keySet());
    }

    @Override
    public void update(String key, Long value) {
        log.error("not support update DeviceIndex, the value must be the primary key");
    }

    @Override
    public void delete(String key, Long value) {
        log.info("DeviceIndex, before delete the key set is {}", map.keySet());
        map.remove(key);
        log.info("DeviceIndex, after delete the key set is {}", map.keySet());
    }
}