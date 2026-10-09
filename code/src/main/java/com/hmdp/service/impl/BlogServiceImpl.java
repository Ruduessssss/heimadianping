package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public  class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;

    @Override
    public Result queryBlogById(Long id) {

        Blog blog = getById(id);


        return queryBlogUser(blog);
    }



    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = blogService.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            queryBlogUser(blog);
            isLike(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result likeBlog(Long id) {
        Blog blog = getById(id);
        String key =BLOG_LIKED_KEY+id;

        Long userId = UserHolder.getUser().getId();
        //判断set里是否有该用户
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(userId));
        if(score==null)
        {
            //没有,在数据库中更新点赞数量,在set中添加
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            if(success)
            {
                stringRedisTemplate.opsForZSet().add(key, String.valueOf(userId),System.currentTimeMillis());
            }
        }
        else
        {
            //没有,在数据库中更新点赞数量,在set中添加
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            if(success)
            {
                stringRedisTemplate.opsForZSet().remove(key, String.valueOf(userId));
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        Blog blog = getById(id);
        String key = BLOG_LIKED_KEY + id;
        //获取用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5==null||top5.isEmpty())
        {
            return Result.ok(Collections.emptyList());
        }
        //解析用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String strids = StrUtil.join(",", ids);

        //获取用户并转成安全类型
        List<UserDTO> userDTOS = userService.query()
                .in("id",ids).last("ORDER BY FIELD(id,"+strids+")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = blogService.save(blog);
        if (!isSuccess) {
            return Result.fail("新增博客失败");
        }
        //获取所有粉丝
        List<Follow> follows = followService.query().eq("follow_user_id", userId).list();
        for (Follow follow : follows) {
            //找到每个粉丝的收件箱
            String key = FEED_KEY+follow.getUserId();
            //存入博客id
            stringRedisTemplate.opsForZSet().add(key,String.valueOf(blog.getId()),System.currentTimeMillis());

        }

        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {

        //查询当前用户id
        Long userId = UserHolder.getUser().getId();

        //查询用户收件箱,分页查询
        String key = FEED_KEY+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, lastId, offset, 2);
        if(typedTuples==null||typedTuples.isEmpty())
        {
            return Result.ok();
        }
        //解析博客id和偏移量,最小的时间戳
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        Integer nextOffSet = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            String blogId = tuple.getValue();
            ids.add(Long.valueOf(blogId));
            long time = tuple.getScore().longValue();
            if(time == minTime)
            {
                nextOffSet++;
            }
            else
            {
                minTime = time;
                nextOffSet = 1;
            }

        }
        //数据库查询博客
        String strIds = StrUtil.join(",", ids);
        List<Blog> blog = blogService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + strIds + ")").list();

        for (Blog blog1 : blog) {
            queryBlogUser(blog1);
            isLike(blog1);
        }

        //封装结果
        ScrollResult r = new ScrollResult();
        r.setList(blog);
        r.setOffset(nextOffSet);
        r.setMinTime(minTime);

        return Result.ok(r);
    }

    private Result queryBlogUser(Blog blog) {

        if(blog==null)
        {
            return Result.fail("博客不存在");
        }


        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isLike(blog);
        return Result.ok(blog);
    }

    private void isLike(Blog blog)
    {
        UserDTO user = UserHolder.getUser();
        if(user==null)
        {
            //用户未登录,无需查询是否点赞
            return ;
        }

        Long id = blog.getId();
        Long userId = user.getId();
        String key = "blog:liked:"+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, String.valueOf(userId));
        if(score!=null)
        {
            //如果用户点过赞
            blog.setIsLike(true);
        }
        else
        {
            blog.setIsLike(false);
        }
    }
}
