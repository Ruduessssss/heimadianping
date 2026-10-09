package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import com.sun.xml.internal.bind.v2.TODO;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.aop.framework.AopProxy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.stream.StreamReceiver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService iSeckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SECKILL_ORDER_SCRIPT;
    static {
        SECKILL_ORDER_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ORDER_SCRIPT.setLocation(new ClassPathResource("seckillOrder.lua"));
        SECKILL_ORDER_SCRIPT.setResultType(Long.class);
    }

    private IVoucherOrderService proxy;

    private ExecutorService SECKILL_ORDER_EXCUTOR = Executors.newSingleThreadExecutor();

    //private BlockingQueue<VoucherOrder> queue = new ArrayBlockingQueue<>(1024*1024);
    @PostConstruct
    private void init()
    {

        SECKILL_ORDER_EXCUTOR.submit(new VoucherOrderHandler());
    }



    private class VoucherOrderHandler implements Runnable
    {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true)
            {
                try {
                    //获取消息队列的信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //判断消息获取是否成功
                    if(list==null||list.isEmpty())
                    {
                        //失败,进入循环
                        continue;
                    }
                    //成功,解析信息,下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    RecordId id = record.getId();
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    HandleVoucherOrder(voucherOrder);

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",id);

                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    //出现异常,调用异常处理函数
                    HandlePendingList();
                }

            }
        }

        private void HandlePendingList() {
            while (true)
            {
                try {
                    //获取消息队列的信息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    //判断消息获取是否成功
                    if(list==null||list.isEmpty())
                    {
                        //说明list没有待处理信息,退出
                        break;
                    }
                    //失败,解析信息,下单
                    MapRecord<String, Object, Object> record = list.get(0);
                    RecordId id = record.getId();
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    HandleVoucherOrder(voucherOrder);

                    //ACK确认
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",id);

                } catch (Exception e) {
                    log.error("处理PendingList订单异常",e);
                    //出现异常,调用异常处理函数,进入循环继续读取
                    try {
                        //睡眠防止频繁循环
                        Thread.sleep(1000);
                    } catch (InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }

            }
        }
    }

    private void HandleVoucherOrder(VoucherOrder order) {
        RLock lock = redissonClient.getLock("order:" + order.getUserId());

        if (!lock.tryLock()) {
            //不成功,直接报错
            log.error("用户不能重复下单");
            return;
        }
        //成功
        try{
           proxy.creatVoucherOrder(order);
        }catch (Exception e)
        {
            throw new  RuntimeException (e);
        }
        finally {
            lock.unlock();
        }

    }
    public Result seckillVoucher(Long voucherId){
        Long userId = UserHolder.getUser().getId();
        Long orderId = redisIdWorker.nextId("order");

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_ORDER_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(),String.valueOf(orderId)
        );

        //判断result是否为0

        if(result!=0)
        {
            return Result.fail(result==1?"库存不足":"用户重复下单");
        }


        //返回订单id
        return Result.ok(orderId);
    }

//    public Result seckillVoucher(Long voucherId){
//        Long userId = UserHolder.getUser().getId();
//
//        //1.执行lua脚本
//        Long result = stringRedisTemplate.execute(
//                SECKILL_ORDER_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(), userId.toString()
//        );
//
//        //判断result是否为0
//
//        if(result!=0)
//        {
//            return Result.fail(result==1?"库存不足":"用户重复下单");
//        }
//        //不为0,放入阻塞队列
//        //TODO
//        //将信息封装成订单
//        Long orderId = redisIdWorker.nextId("order");
//        VoucherOrder voucherOrder = new VoucherOrder();
//        voucherOrder.setId(orderId);
//        voucherOrder.setVoucherId(voucherId);
//        voucherOrder.setUserId(UserHolder.getUser().getId());
//
//        queue.add(voucherOrder);
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        //返回订单id
//        return Result.ok(orderId);
//    }


//    @Override
//    public Result seckillVoucher(Long voucherId){
//
//        //查询优惠券
//        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
//
//        //判断秒杀时间是否未开始,超时
//        if ((voucher.getBeginTime().isAfter(LocalDateTime.now())||voucher.getEndTime().isBefore(LocalDateTime.now()))) {
//            return  Result.fail("秒杀时间不符合当前");
//        }
//
//        //判断库存是否不足
//        if(voucher.getStock()<1)
//        {
//            return Result.fail("库存不足");
//        }
//        Long userId = UserHolder.getUser().getId();
//
//
//        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:"+userId, stringRedisTemplate);
//        RLock lock = redissonClient.getLock("order:" + userId);
//
//        if (!lock.tryLock()) {
//            //不成功,直接报错
//            return Result.fail("同一用户不能重复下单");
//        }
//        //成功
//        try{
//            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();
//            return o.creatVoucherOrder(voucherId);
//        }catch (Exception e)
//        {
//            throw new  RuntimeException (e);
//        }
//        finally {
//            lock.unlock();
//        }
//
//
//        //根据用户id加锁,intern保证引用是同一个对象
//        //        {
//        //            IVoucherOrderService o = (IVoucherOrderService) AopContext.currentProxy();
//        //            return o.creatVoucherOrder(voucherId);
//        //        }
//    }

@Transactional
    public void creatVoucherOrder(VoucherOrder order)
    {
        Long userId = order.getUserId();

        Long voucherId = order.getVoucherId();
        Integer count = query().eq("voucher_id", voucherId).eq("user_id", userId).count();

        if(count>0)
        {
             log.error("每个用户只能下一单!");
             return;
        }

        //扣减库存,考虑到超卖,使用乐观锁()
        //方法一,如果cas中stock和当前对象get的stock不一致就失败->导致多线程抢一个秒杀券会有高失败率
        //业务上我们只需要保证stock大于0,就可以继续购买,因此采用方案二
        //boolean success = iSeckillVoucherService.update()
        // .setSql("stock = stock - 1").eq("voucher_id", voucherId).eq("stock",voucher.getStock())
        // .update();

        boolean success = iSeckillVoucherService.update().
                setSql("stock = stock -1 ")
                .eq("voucher_id", voucherId).gt("stock", 0).update();
        if(!success)
        {
            log.error("库存不足");
           return;
        }

        //创建订单

        save(order);
        return ;
    }

}


