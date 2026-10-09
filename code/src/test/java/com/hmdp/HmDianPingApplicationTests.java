package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    public IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Test
    public void testSaveHotShop()
    {
        shopService.saveShop2Redis(1L,1L, TimeUnit.SECONDS);
    }


    @Test
    public void loadShopData()
    {
        //获取数据列表
        List<Shop> shops = shopService.list();
        //将商铺按照商铺类型分类
        Map<Long, List<Shop>> map = shops.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //不同类型存到redis geo
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            //获取同类店铺和类型id
            List<Shop> shopList = entry.getValue();
            Long typeId = entry.getKey();

            String key = SHOP_GEO_KEY+typeId;
            //方法一:依次存入redis,每存入一个店铺发起一次请求,麻烦
            for (Shop shop : shopList) {
                stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()), String.valueOf(shop.getId()));
            }

            //方法二:用另一种add,封装到list
            List<RedisGeoCommands.GeoLocation<String>> list = new ArrayList<>(shopList.size());
            for (Shop shop : shopList) {
                list.add(new RedisGeoCommands.GeoLocation<String>(String.valueOf(shop.getId()),new Point(shop.getX(),shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key,list);
        }
    }
}
