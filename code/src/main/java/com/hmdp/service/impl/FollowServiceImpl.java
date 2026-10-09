package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.controller.FollowController;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import com.sun.net.httpserver.Authenticator;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

@Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService iUserService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            //关注了,新增数据
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            boolean isSuccess = save(follow);
            if(isSuccess)
            {
                String key = "Follow:"+userId;
                stringRedisTemplate.opsForSet().add(key,String.valueOf(id));
            }

        }
        else
        {
            //没关注,删除数据
            boolean isSuccess = remove(new QueryWrapper<Follow>().eq("user_id", userId).eq("follow_user_id", id));
            if (isSuccess) {
                String key = "Follow:"+userId;
                stringRedisTemplate.opsForSet().remove(key,String.valueOf(id));
            }
        }

        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        //获取当前用户id
        Long userId = UserHolder.getUser().getId();
        //判断是否关注
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();

        return Result.ok(count>0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();


        String key1 = "Follow:"+userId;
        String key2 = "Follow:"+id;
        //查找两个用户关注的交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if(intersect==null||intersect.isEmpty())
        {
            return Result.ok(Collections.emptyList());
        }
        //解析用户id
        List<Long> ids = intersect.stream().map(user -> Long.valueOf(user)).collect(Collectors.toList());
        //转成安全类型
        List<UserDTO> userDTOS = iUserService.listByIds(ids).stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());

        return Result.ok(userDTOS);

    }
}
