/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.IntegerDeserializer;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.kstream.JoinWindowSpec;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.ValueJoiner;
import org.apache.kafka.test.KStreamTestDriver;
import org.apache.kafka.test.MockProcessorSupplier;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class KStreamKStreamJoinTest {

    private String topic1 = "topic1";
    private String topic2 = "topic2";

    private IntegerSerializer keySerializer = new IntegerSerializer();
    private StringSerializer valSerializer = new StringSerializer();
    private IntegerDeserializer keyDeserializer = new IntegerDeserializer();
    private StringDeserializer valDeserializer = new StringDeserializer();

    private ValueJoiner<String, String, String> joiner = new ValueJoiner<String, String, String>() {
        @Override
        public String apply(String value1, String value2) {
            return value1 + "+" + value2;
        }
    };

    @Test
    public void testJoin() throws Exception {
        File baseDir = Files.createTempDirectory("test").toFile();
        try {

            KStreamBuilder builder = new KStreamBuilder();

            final int[] expectedKeys = new int[]{0, 1, 2, 3};

            KStream<Integer, String> stream1;
            KStream<Integer, String> stream2;
            KStream<Integer, String> joined;
            MockProcessorSupplier<Integer, String> processor;

            processor = new MockProcessorSupplier<>();
            stream1 = builder.from(keyDeserializer, valDeserializer, topic1);
            stream2 = builder.from(keyDeserializer, valDeserializer, topic2);
            joined = stream1.join(stream2, joiner, JoinWindowSpec.of("test").within(100),
                    keySerializer, valSerializer, valSerializer, keyDeserializer, valDeserializer, valDeserializer);
            joined.process(processor);

            Collection<Set<String>> copartitionGroups = builder.copartitionGroups();

            assertEquals(1, copartitionGroups.size());
            assertEquals(new HashSet<>(Arrays.asList(topic1, topic2)), copartitionGroups.iterator().next());

            KStreamTestDriver driver = new KStreamTestDriver(builder, baseDir);
            driver.setTime(0L);

            // push two items to the primary stream. the other window is empty
            // w1 = {}
            // w2 = {}
            // --> w1 = { 0:X1, 1:X1 }
            //     w2 = {}

            for (int i = 0; i < 2; i++) {
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            // push two items to the other stream. this should produce two items.
            // w1 = { 0:X0, 1:X1 }
            // w2 = {}
            // --> w1 = { 0:X1, 1:X1 }
            //     w2 = { 0:Y0, 1:Y1 }

            for (int i = 0; i < 2; i++) {
                driver.process(topic2, expectedKeys[i], "Y" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+Y0", "1:X1+Y1");

            // push all four items to the primary stream. this should produce two items.
            // w1 = { 0:X0, 1:X1 }
            // w2 = { 0:Y0, 1:Y1 }
            // --> w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3 }
            //     w2 = { 0:Y0, 1:Y1 }

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+Y0", "1:X1+Y1");

            // push all items to the other stream. this should produce six items.
            // w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3 }
            // w2 = { 0:Y0, 1:Y1 }
            // --> w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3 }
            //     w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3 }

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YY0", "0:X0+YY0", "1:X1+YY1", "1:X1+YY1", "2:X2+YY2", "3:X3+YY3");

            // push all four items to the primary stream. this should produce six items.
            // w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3 }
            // w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3
            // --> w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3,  0:XX0, 1:XX1, 2:XX2, 3:XX3 }
            //     w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3 }

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:XX0+Y0", "0:XX0+YY0", "1:XX1+Y1", "1:XX1+YY1", "2:XX2+YY2", "3:XX3+YY3");

            // push two items to the other stream. this should produce six item.
            // w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3,  0:XX0, 1:XX1, 2:XX2, 3:XX3 }
            // w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3
            // --> w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3,  0:XX0, 1:XX1, 2:XX2, 3:XX3 }
            //     w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3, 0:YYY0, 1:YYY1 }

            for (int i = 0; i < 2; i++) {
                driver.process(topic2, expectedKeys[i], "YYY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YYY0", "0:X0+YYY0", "0:XX0+YYY0", "1:X1+YYY1", "1:X1+YYY1", "1:XX1+YYY1");

        } finally {
            Utils.delete(baseDir);
        }
    }

    @Test
    public void testOuterJoin() throws Exception {
        File baseDir = Files.createTempDirectory("test").toFile();
        try {

            KStreamBuilder builder = new KStreamBuilder();

            final int[] expectedKeys = new int[]{0, 1, 2, 3};

            KStream<Integer, String> stream1;
            KStream<Integer, String> stream2;
            KStream<Integer, String> joined;
            MockProcessorSupplier<Integer, String> processor;

            processor = new MockProcessorSupplier<>();
            stream1 = builder.from(keyDeserializer, valDeserializer, topic1);
            stream2 = builder.from(keyDeserializer, valDeserializer, topic2);
            joined = stream1.outerJoin(stream2, joiner, JoinWindowSpec.of("test").within(100),
                    keySerializer, valSerializer, valSerializer, keyDeserializer, valDeserializer, valDeserializer);
            joined.process(processor);

            Collection<Set<String>> copartitionGroups = builder.copartitionGroups();

            assertEquals(1, copartitionGroups.size());
            assertEquals(new HashSet<>(Arrays.asList(topic1, topic2)), copartitionGroups.iterator().next());

            KStreamTestDriver driver = new KStreamTestDriver(builder, baseDir);
            driver.setTime(0L);

            // push two items to the primary stream. the other window is empty.this should produce two items
            // w1 = {}
            // w2 = {}
            // --> w1 = { 0:X1, 1:X1 }
            //     w2 = {}

            for (int i = 0; i < 2; i++) {
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+null", "1:X1+null");

            // push two items to the other stream. this should produce two items.
            // w1 = { 0:X0, 1:X1 }
            // w2 = {}
            // --> w1 = { 0:X1, 1:X1 }
            //     w2 = { 0:Y0, 1:Y1 }

            for (int i = 0; i < 2; i++) {
                driver.process(topic2, expectedKeys[i], "Y" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+Y0", "1:X1+Y1");

            // push all four items to the primary stream. this should produce four items.
            // w1 = { 0:X0, 1:X1 }
            // w2 = { 0:Y0, 1:Y1 }
            // --> w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3 }
            //     w2 = { 0:Y0, 1:Y1 }

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+Y0", "1:X1+Y1", "2:X2+null", "3:X3+null");

            // push all items to the other stream. this should produce six items.
            // w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3 }
            // w2 = { 0:Y0, 1:Y1 }
            // --> w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3 }
            //     w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3 }

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YY0", "0:X0+YY0", "1:X1+YY1", "1:X1+YY1", "2:X2+YY2", "3:X3+YY3");

            // push all four items to the primary stream. this should produce six items.
            // w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3 }
            // w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3
            // --> w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3,  0:XX0, 1:XX1, 2:XX2, 3:XX3 }
            //     w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3 }

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:XX0+Y0", "0:XX0+YY0", "1:XX1+Y1", "1:XX1+YY1", "2:XX2+YY2", "3:XX3+YY3");

            // push two items to the other stream. this should produce six item.
            // w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3,  0:XX0, 1:XX1, 2:XX2, 3:XX3 }
            // w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3
            // --> w1 = { 0:X1, 1:X1, 0:X0, 1:X1, 2:X2, 3:X3,  0:XX0, 1:XX1, 2:XX2, 3:XX3 }
            //     w2 = { 0:Y0, 1:Y1, 0:YY0, 0:YY0, 1:YY1, 2:YY2, 3:YY3, 0:YYY0, 1:YYY1 }

            for (int i = 0; i < 2; i++) {
                driver.process(topic2, expectedKeys[i], "YYY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YYY0", "0:X0+YYY0", "0:XX0+YYY0", "1:X1+YYY1", "1:X1+YYY1", "1:XX1+YYY1");

        } finally {
            Utils.delete(baseDir);
        }
    }

    @Test
    public void testWindowing() throws Exception {
        File baseDir = Files.createTempDirectory("test").toFile();
        try {

            long time = 0L;

            KStreamBuilder builder = new KStreamBuilder();

            final int[] expectedKeys = new int[]{0, 1, 2, 3};

            KStream<Integer, String> stream1;
            KStream<Integer, String> stream2;
            KStream<Integer, String> joined;
            MockProcessorSupplier<Integer, String> processor;

            processor = new MockProcessorSupplier<>();
            stream1 = builder.from(keyDeserializer, valDeserializer, topic1);
            stream2 = builder.from(keyDeserializer, valDeserializer, topic2);
            joined = stream1.join(stream2, joiner, JoinWindowSpec.of("test").within(100),
                    keySerializer, valSerializer, valSerializer, keyDeserializer, valDeserializer, valDeserializer);
            joined.process(processor);

            Collection<Set<String>> copartitionGroups = builder.copartitionGroups();

            assertEquals(1, copartitionGroups.size());
            assertEquals(new HashSet<>(Arrays.asList(topic1, topic2)), copartitionGroups.iterator().next());

            KStreamTestDriver driver = new KStreamTestDriver(builder, baseDir);
            driver.setTime(time);

            // push two items to the primary stream. the other window is empty. this should produce no items.
            // w1 = {}
            // w2 = {}
            // --> w1 = { 0:X1, 1:X1 }
            //     w2 = {}

            for (int i = 0; i < 2; i++) {
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            // push two items to the other stream. this should produce two items.
            // w1 = { 0:X0, 1:X1 }
            // w2 = {}
            // --> w1 = { 0:X1, 1:X1 }
            //     w2 = { 0:Y0, 1:Y1 }

            for (int i = 0; i < 2; i++) {
                driver.process(topic2, expectedKeys[i], "Y" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+Y0", "1:X1+Y1");

            // clear logically
            time = 1000L;

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.setTime(time + i);
                driver.process(topic1, expectedKeys[i], "X" + expectedKeys[i]);
            }
            processor.checkAndClearResult();

            // gradually expires items in w1
            // w1 = { 0:X0, 1:X1, 2:X2, 3:X3 }

            time = 1000 + 100L;
            driver.setTime(time);

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YY0", "1:X1+YY1", "2:X2+YY2", "3:X3+YY3");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("1:X1+YY1", "2:X2+YY2", "3:X3+YY3");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("2:X2+YY2", "3:X3+YY3");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("3:X3+YY3");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            // go back to the time before expiration

            time = 1000L - 100L - 1L;
            driver.setTime(time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YY0");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YY0", "1:X1+YY1");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YY0", "1:X1+YY1", "2:X2+YY2");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic2, expectedKeys[i], "YY" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:X0+YY0", "1:X1+YY1", "2:X2+YY2", "3:X3+YY3");

            // clear (logically)
            time = 2000L;

            for (int i = 0; i < expectedKeys.length; i++) {
                driver.setTime(time + i);
                driver.process(topic2, expectedKeys[i], "Y" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            // gradually expires items in w2
            // w2 = { 0:Y0, 1:Y1, 2:Y2, 3:Y3 }

            time = 2000L + 100L;
            driver.setTime(time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:XX0+Y0", "1:XX1+Y1", "2:XX2+Y2", "3:XX3+Y3");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("1:XX1+Y1", "2:XX2+Y2", "3:XX3+Y3");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("2:XX2+Y2", "3:XX3+Y3");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("3:XX3+Y3");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            // go back to the time before expiration

            time = 2000L - 100L - 1L;
            driver.setTime(time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult();

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:XX0+Y0");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:XX0+Y0", "1:XX1+Y1");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:XX0+Y0", "1:XX1+Y1", "2:XX2+Y2");

            driver.setTime(++time);
            for (int i = 0; i < expectedKeys.length; i++) {
                driver.process(topic1, expectedKeys[i], "XX" + expectedKeys[i]);
            }

            processor.checkAndClearResult("0:XX0+Y0", "1:XX1+Y1", "2:XX2+Y2", "3:XX3+Y3");

        } finally {
            Utils.delete(baseDir);
        }
    }

}
