package tn.enit.tp4.processor;

import java.util.*;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.*;
import org.apache.spark.streaming.kafka010.*;

import tn.enit.tp4.entity.Humidity;
import tn.enit.tp4.entity.SensorData;
import tn.enit.tp4.entity.Temperature;
import tn.enit.tp4.util.SensorDataDeserializer;

import tn.enit.tp4.util.PropertyFileReader;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;

public class StreamProcessor {

    public static void main(String[] args) throws Exception {

        String file = "spark-processor.properties";
        Properties prop = PropertyFileReader.readPropertyFile(file);

        SparkConf conf = ProcessorUtils.getSparkConf(prop);

        JavaStreamingContext streamingContext = new JavaStreamingContext(conf, Durations.seconds(10));
        JavaSparkContext sc = streamingContext.sparkContext();


        streamingContext.checkpoint(prop.getProperty("tn.enit.tp4.spark.checkpoint.dir"));

        Map<String, Object> kafkaParams = new HashMap<>();
        kafkaParams.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, prop.getProperty("tn.enit.tp4.brokerlist"));
        kafkaParams.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        kafkaParams.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, SensorDataDeserializer.class);
        kafkaParams.put(ConsumerConfig.GROUP_ID_CONFIG, prop.getProperty("tn.enit.tp4.topic"));
        kafkaParams.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, prop.getProperty("tn.enit.tp4.resetType"));
        kafkaParams.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        Collection<String> topics = Arrays.asList(prop.getProperty("tn.enit.tp4.topic"));

        JavaInputDStream<ConsumerRecord<String, SensorData>> stream = KafkaUtils.createDirectStream(streamingContext,
                LocationStrategies.PreferConsistent(),
                ConsumerStrategies.<String, SensorData>Subscribe(topics, kafkaParams));

        JavaDStream<SensorData> sensordataStream = stream.map(v -> {
            return v.value();
        });

        sensordataStream.print();

        JavaDStream<Temperature> temperatureStream = sensordataStream.map(v -> {
            return new Temperature(v.getId(), v.getTemperature(), v.getTimestamp());
        });
        temperatureStream.print();

        JavaDStream<Humidity> humidityStream = sensordataStream.map(v -> {
            return new Humidity(v.getId(), v.getHumidity(), v.getTimestamp());
        });

        // save data to cassandra => stream
        ProcessorUtils.saveTemperatureToCassandra(temperatureStream);

        ProcessorUtils.saveHumidityToCassandra(humidityStream);

        // batch process
        // save data to HDFS => batch
        SparkSession sparkSession = SparkSession.builder().config(conf).getOrCreate();
        String saveFile = prop.getProperty("tn.enit.tp4.hdfs") + "iot-data";
        System.out.println("Persisting to HDFS !!!!!");
        ProcessorUtils.saveDataToHDFS(sensordataStream, saveFile, sparkSession);

        // sparkSession.close();
        // sparkSession.stop();




        streamingContext.start();
        streamingContext.awaitTermination();

    }

}
