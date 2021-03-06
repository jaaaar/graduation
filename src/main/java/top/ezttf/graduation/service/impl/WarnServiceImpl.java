package top.ezttf.graduation.service.impl;

import com.google.common.collect.Lists;
import com.spring4all.spring.boot.starter.hbase.api.HbaseTemplate;
import lombok.extern.slf4j.Slf4j;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.http.client.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import top.ezttf.graduation.constant.Constants;
import top.ezttf.graduation.service.IWarnService;
import top.ezttf.graduation.utils.RowKeyGenUtil;
import top.ezttf.graduation.vo.Warn;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author yuwen
 * @date 2019/3/21
 */
@Slf4j
@Service
public class WarnServiceImpl implements IWarnService {

    private AtomicLong num = new AtomicLong(0L);

    private final HbaseTemplate hbaseTemplate;

    @Autowired
    public WarnServiceImpl(HbaseTemplate hbaseTemplate) {
        this.hbaseTemplate = hbaseTemplate;
    }

    /**
     * @param warn
     */
    @Override
    public void saveWarn(Warn warn) {
        String mmac = RowKeyGenUtil.genWarnRowKey(warn);

        List<Mutation> datas = Lists.newArrayList();
        byte[] FAMILY_D = Constants.WarnTable.FAMILY_D.getBytes();
        byte[] ID = Constants.WarnTable.ID.getBytes();
        byte[] MMAC = Constants.WarnTable.MMAC.getBytes();
        byte[] FAMILY_I = Constants.WarnTable.FAMILY_I.getBytes();
        byte[] COUNT = Constants.WarnTable.COUNT.getBytes();
        byte[] FAMILY_T = Constants.WarnTable.FAMILY_T.getBytes();
        byte[] TIME = Constants.WarnTable.TIME.getBytes();

        Put put = new Put(Bytes.toBytes(mmac));
        put.addColumn(FAMILY_D, ID, Bytes.toBytes(warn.getId()));
        put.addColumn(FAMILY_D, MMAC, Bytes.toBytes(warn.getMmac()));
        put.addColumn(FAMILY_I, COUNT, Bytes.toBytes(warn.getCount()));
        String pattern = "yyyy-MM-dd HH:mm:ss";
        put.addColumn(FAMILY_T, TIME, Bytes.toBytes(DateUtils.formatDate(warn.getTime(), pattern)));
        datas.add(put);
        hbaseTemplate.saveOrUpdates(Constants.WarnTable.TABLE_NAME, datas);
        log.debug("warn count: {}", num.incrementAndGet());
    }



}
