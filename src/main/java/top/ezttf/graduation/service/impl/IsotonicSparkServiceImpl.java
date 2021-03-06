package top.ezttf.graduation.service.impl;

import com.alibaba.fastjson.JSON;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.spring4all.spring.boot.starter.hbase.api.HbaseTemplate;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.mapreduce.TableInputFormat;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.ml.feature.VectorAssembler;
import org.apache.spark.ml.regression.IsotonicRegressionModel;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.springframework.stereotype.Service;
import top.ezttf.graduation.constant.Constants;
import top.ezttf.graduation.index.DataTable;
import top.ezttf.graduation.index.DeviceIndex;
import top.ezttf.graduation.service.IPredicateService;
import top.ezttf.graduation.service.IRegressionService;
import top.ezttf.graduation.service.ISparkService;
import top.ezttf.graduation.utils.CommonUtils;
import top.ezttf.graduation.vo.MlLibWarn;
import top.ezttf.graduation.vo.MlLibWifi;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * @author yuwen
 * @date 2019/4/28
 */
@Service(value = "isotonicSparkServiceImpl")
public class IsotonicSparkServiceImpl implements ISparkService {


    private IsotonicRegressionModel warnModel;
    private IsotonicRegressionModel wifiModel;

    private final IPredicateService iPredicateService;
    private final DeviceIndex deviceIndex;
    private final JavaSparkContext sparkContext;
    private final IRegressionService iRegressionService;
    private final HbaseTemplate hbaseTemplate;

    public IsotonicSparkServiceImpl(
            IRegressionService iRegressionService,
            HbaseTemplate hbaseTemplate,
            JavaSparkContext sparkContext,
            DeviceIndex deviceIndex,
            IPredicateService iPredicateService) {
        this.iRegressionService = iRegressionService;
        this.hbaseTemplate = hbaseTemplate;
        this.sparkContext = sparkContext;
        this.deviceIndex = deviceIndex;
        this.iPredicateService = iPredicateService;
    }

    @Override
    public void trainWarn() {
        Configuration configuration = hbaseTemplate.getConfiguration();
        configuration.set(TableInputFormat.INPUT_TABLE, Constants.WarnTable.TABLE_NAME);
        JavaPairRDD<ImmutableBytesWritable, Result> hbaseRDD = sparkContext.newAPIHadoopRDD(
                configuration,
                TableInputFormat.class,
                ImmutableBytesWritable.class,
                Result.class
        );

        ThreadLocalRandom random = ThreadLocalRandom.current();
        JavaRDD<MlLibWarn> javaRDD = hbaseRDD.map(v1 -> {
            Result result = v1._2();
            String t = Bytes.toString(result.getValue(
                    Constants.WarnTable.FAMILY_T.getBytes(),
                    Constants.WarnTable.TIME.getBytes()
            ));
            long time = Timestamp.valueOf(t).getTime();
            LocalDateTime localDateTime = LocalDateTime.ofEpochSecond(time, 0, ZoneOffset.UTC);
            int hour = localDateTime.getHour();
            int min = localDateTime.getMinute();
            int sec = localDateTime.getSecond();
            time = 1000 * 3600 * hour +
                    1000 * 60 * min +
                    1000 * sec +
                    ThreadLocalRandom.current().nextInt(0, 1000);
            long count = Bytes.toLong(result.getValue(
                    Constants.WarnTable.FAMILY_I.getBytes(),
                    Constants.WarnTable.COUNT.getBytes()
            ));
            return new MlLibWarn((double) time, (double) count, random.nextDouble());
        }).distinct();

        SparkSession sparkSession = SparkSession.builder().sparkContext(sparkContext.sc()).getOrCreate();
        Dataset<Row> dataset = sparkSession.createDataFrame(javaRDD, MlLibWarn.class);
        dataset = dataset.sort("random");

        IsotonicRegressionModel isotonicRegressionModel = iRegressionService.isotonicRegressionTrain(
                dataset,
                new String[]{"time"},
                "count"
        );

        // 缓存模型
        warnModel = isotonicRegressionModel;
    }

    @Override
    public void trainWifi() {
        Configuration configuration = hbaseTemplate.getConfiguration();
        configuration.set(TableInputFormat.INPUT_TABLE, Constants.WifiTable.TABLE_NAME);
        JavaPairRDD<ImmutableBytesWritable, Result> hbaseRDD = sparkContext.newAPIHadoopRDD(
                configuration,
                TableInputFormat.class,
                ImmutableBytesWritable.class,
                Result.class
        );
        Map<String, List<String>> map = Maps.newHashMap();
        JavaRDD<Map<String, List<String>>> javaRDD = hbaseRDD.map(immutableBytesWritableResultTuple2 -> {
            Result result = immutableBytesWritableResultTuple2._2();
            String mac = Bytes.toString(result.getValue(
                    Constants.WifiTable.FAMILY_U.getBytes(),
                    Constants.WifiTable.MAC.getBytes()
            ));
            mac = StringUtils.substring(mac, 0, mac.indexOf("-"));
            String mMac = Bytes.toString(result.getValue(
                    Constants.WifiTable.FAMILY_D.getBytes(),
                    Constants.WifiTable.MMAC.getBytes()
            ));
            List<String> list = map.computeIfAbsent(mac, s -> new ArrayList<>());
            list.add(mMac);
            return map;
        }).distinct();
        List<Map<String, List<String>>> collect = javaRDD.collect();
        Map<String, List<String>> finalMap = collect.get(0);
        // 初始化只带表头的dataSet
        SparkSession sparkSession = SparkSession.builder().sparkContext(sparkContext.sc()).getOrCreate();
        Dataset<Row> dataset = sparkSession.createDataFrame(Lists.newArrayList(), MlLibWifi.class);

        List<List<String>> mMacList = finalMap.values()
                .stream()
                .filter(mMacs -> mMacs.size() >= 2)
                .collect(Collectors.toList());
        for (List<String> mMacs : mMacList) {
            this.distinct(mMacs);
            System.out.println("======================================");
            System.out.println(JSON.toJSONString(mMacs));
            System.out.println("======================================");
            List<Double> ids = this.transformMMacs2Double(mMacs);
            List<MlLibWifi> mlLibWifis = this.assembleMlLibWifis(ids);

            Dataset<Row> dataFrame = sparkSession.createDataFrame(mlLibWifis, MlLibWifi.class);
            dataset = dataset.union(dataFrame);
        }
        dataset = dataset.sort("random");
        // 缓存模型
        wifiModel = iRegressionService.isotonicRegressionTrain(
                dataset,
                new String[]{"lastGeo"},
                "nowGeo"
        );
        VectorAssembler assembler = new VectorAssembler().setInputCols(new String[]{"lastGeo"}).setOutputCol("features");
        dataset = assembler.transform(dataset);
        Dataset<Row> transform = wifiModel.transform(dataset);
        transform.show();
    }

    @Override
    public Map<String, Double> predicateWarn() {
        if (warnModel == null) {
            this.trainWarn();
        }
        Instant now = Instant.now();
        long start = now.toEpochMilli();
        // 预测一个小时范围内
        // 预测一天范围内
        long end = now.plus(1, ChronoUnit.DAYS).toEpochMilli();
        List<MlLibWarn> mlLibWarnList = Lists.newArrayList();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // 五分钟一条数据(对应dataset的一行)
        // 一小时一条数据
        for (long i = start; i <= end; i += 60 * 60 * 1000) {
            mlLibWarnList.add(new MlLibWarn(i, 0d, random.nextDouble()));
        }
        SparkSession sparkSession = SparkSession.builder().sparkContext(sparkContext.sc()).getOrCreate();
        Dataset<Row> dataset = sparkSession.createDataFrame(mlLibWarnList, MlLibWarn.class).sort("random");

        Dataset<Row> result = iPredicateService.isotonicRegressionPredicate(
                warnModel,
                dataset,
                new String[]{"time"},
                "features"
        );
        result = result.sort("time");
        result.show();
        List<Row> rows = result.select("time", "prediction").collectAsList();
        Map<String, Double> map = Maps.newLinkedHashMap();
        SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");
        for (Row row : rows) {
            double rowDouble = row.getDouble(0);
            String time = format.format(rowDouble);
            double prediction = row.getDouble(1);
            prediction = prediction - ThreadLocalRandom.current().nextInt(40, (int)prediction - 40);
            map.put(time, prediction);
        }
        return map;
    }

    @Override
    public List<Long> predicateWifi(double lastGeo) {
        if (wifiModel == null) {
            this.trainWifi();
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Long> list = Lists.newArrayList();
        int count = 0;

        for (int i = 0; i < 5 && !list.contains((long)lastGeo); i++) {
            list.add((long) lastGeo);
            MlLibWifi mlLibWifi = new MlLibWifi(lastGeo, 0d, random.nextDouble());
            SparkSession sparkSession = SparkSession.builder().sparkContext(sparkContext.sc()).getOrCreate();
            Dataset<Row> dataset = sparkSession.createDataFrame(
                    Lists.newArrayList(mlLibWifi), MlLibWifi.class
            ).sort("random");
            Dataset<Row> result = iPredicateService.isotonicRegressionPredicate(
                    wifiModel,
                    dataset,
                    new String[]{"lastGeo"},
                    "features"
            );
            result.show();
            List<Row> rows = result.select("prediction").limit(1).collectAsList();
            for (Row row : rows) {
                double num = (double)row.get(0);
                Long id = CommonUtils.searchElement(DataTable.of(DeviceIndex.class).getIds(), (long) num);
                lastGeo = id.doubleValue();
            }
        }
        return list;
    }


    @SuppressWarnings("all")
    private List<Double> transformMMacs2Double(List<String> mMacs) {
        if (CollectionUtils.isEmpty(mMacs)) {
            return Collections.EMPTY_LIST;
        }
        return mMacs.stream()
                .map(deviceIndex::get)
                .filter(Objects::nonNull)
                .map(Long::doubleValue)
                .collect(Collectors.toList());
    }


    /**
     * 去除集合中相邻且重复的元素, 字符串
     * A, A, A, B, C, A, A, C     ------->
     * A, B, C, A, C
     *
     * @param iterable 可迭代集合
     */
    private void distinct(Iterable<String> iterable) {
        String last = StringUtils.EMPTY;
        for (Iterator<String> iterator = iterable.iterator(); iterator.hasNext(); ) {
            String now = iterator.next();
            if (StringUtils.equals(last, now)) {
                iterator.remove();
            } else {
                last = now;
            }
        }
    }

    @SuppressWarnings("all")
    private List<MlLibWifi> assembleMlLibWifis(List<Double> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.EMPTY_LIST;
        }
        List<MlLibWifi> list = Lists.newArrayList();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < ids.size() - 1; i++) {
            double pre = ids.get(i);
            double post = ids.get(i + 1);
            list.add(new MlLibWifi(pre, post, random.nextDouble()));
        }
        return list;
    }
}
