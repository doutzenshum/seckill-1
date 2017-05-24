package com.suny.service;

import com.suny.dao.SeckillMapper;
import com.suny.dao.SuccessKilledMapper;
import com.suny.dto.Exposer;
import com.suny.dto.SeckillExecution;
import com.suny.entity.Seckill;
import com.suny.entity.SuccessKilled;
import com.suny.enums.SeckillStatEnum;
import com.suny.exception.RepeatKillException;
import com.suny.exception.SeckillCloseException;
import com.suny.exception.SeckillException;
import com.suny.service.interfaces.SeckillService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Created by 孙建荣 on 17-5-23.下午9:30
 */
@Service
public class SeckillServiceImpl implements SeckillService {
    private Logger logger = LoggerFactory.getLogger(this.getClass());
    /* 加入一个盐值，用于混淆*/
    private final String salt = "thisIsASaltValue";

    @Autowired
    private SeckillMapper seckillMapper;
    @Autowired
    private SuccessKilledMapper successKilledMapper;

    /**
     * 查询全部的秒杀记录.
     *
     * @return 数据库中所有的秒杀记录
     */
    @Override
    public List<Seckill> getSeckillList() {
        return seckillMapper.queryAll(0, 4);
    }

    /**
     * 查询单个秒杀记录
     *
     * @param seckillId 秒杀记录的ID
     * @return 根据ID查询出来的记录信息
     */
    @Override
    public Seckill getById(long seckillId) {
        return seckillMapper.queryById(seckillId);
    }

    /**
     * 在秒杀开启时输出秒杀接口的地址，否则输出系统时间跟秒杀地址
     *
     * @param seckillId 秒杀商品Id
     * @return 根据对应的状态返回对应的状态实体
     */
    @Override
    public Exposer exportSeckillUrl(long seckillId) {
        Seckill seckill = seckillMapper.queryById(seckillId);
        if (seckill == null) {
            logger.warn("查询不到这个秒杀产品的记录");
            return new Exposer(false, seckillId);
        }
        if (seckill != null) {
            // 判断是否还没到秒杀时间或者是过了秒杀时间
            LocalDateTime startTime = seckill.getStartTime();
            LocalDateTime endTime = seckill.getEndTime();
            LocalDateTime nowTime = LocalDateTime.now();
            if (startTime.isBefore(nowTime) || endTime.isAfter(nowTime)) {
                return new Exposer(false, seckillId, nowTime, startTime, endTime);
            }

            //秒杀开启，返回秒杀商品的id,用给接口加密的md5
            String md5 = getMd5(seckillId);
            return new Exposer(true, md5, seckillId);
        }

        return null;
    }

    private String getMd5(long seckillId) {
        String base = seckillId + "/" + salt;
        return DigestUtils.md5DigestAsHex(base.getBytes());
    }

    /**
     * 执行秒杀操作，有可能是失败的，失败我们就抛出异常
     *
     * @param seckillId 秒杀的商品ID
     * @param userPhone 手机号码
     * @param md5       md5加密值
     * @return 根据不同的结果返回不同的实体信息
     */
    @Transactional
    @Override
    public SeckillExecution executeSeckill(long seckillId, long userPhone, String md5) throws SeckillException {
        if (md5 == null || !md5.equals(getMd5(seckillId))) {
            logger.error("秒杀数据被篡改");
            throw new SeckillException("seckill data rewrite");
        }
        // 执行秒杀业务逻辑
        LocalDateTime nowTIme = LocalDateTime.now();

        try {
            //执行减库存操作
            int reduceNumber = seckillMapper.reduceNumber(seckillId, nowTIme);
            if (reduceNumber <= 0) {
                logger.warn("灭有更新数据库记录，说明秒杀结束");
                throw new SeckillException("seckill is closed");
            } else {
                // 这里至少减少的数量不为0了，秒杀成功了就增加一个秒杀成功详细
                int insertCount = successKilledMapper.insertSuccessKilled(seckillId, userPhone);
                // 查看是否被重复插入，即用户是否重复秒杀
                if (insertCount <= 0) {
                    throw new RepeatKillException("seckill repeated");
                } else {
                    // 秒杀成功了，返回那条插入成功秒杀的信息
                    SuccessKilled successKilled = successKilledMapper.queryByIdWithSeckill(seckillId, userPhone);
                    return new SeckillExecution(seckillId, SeckillStatEnum.SUCCESS, successKilled);
                }
            }
        } catch (SeckillCloseException | RepeatKillException e1) {
            throw e1;
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            // 把编译期异常转换为运行时异常
            throw new SeckillException("seckill inner error : " + e.getMessage());
        }

    }
}






