/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.apache.hadoop.hive.ql.hooks;

import static java.util.Collections.singletonList;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.ql.CompilationOpContext;
import org.apache.hadoop.hive.ql.exec.FetchTask;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.hooks.proto.HiveHookEvents;
import org.apache.hadoop.hive.ql.parse.ExplainConfiguration;
import org.apache.hadoop.hive.ql.plan.ExplainWork;
import org.apache.hadoop.hive.ql.plan.FetchWork;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.json.JSONObject;
import org.junit.Test;

public class TestHiveHookEventProtoPartialBuilder {
  private static final String QUERY_1 = "query1";
  private static final String HIVE = "hive";
  private static final String LLAP = "llap";
  private static final String TEZ = "tez";
  private static final long TIMESTAMP = System.currentTimeMillis();

  @Test
  public void testEquality() {
    JSONObject json = new JSONObject();
    json.put("key1", "value1");
    json.put("key2", "value2");
    json.put("key3", "value3");
    HiveHookEvents.HiveHookEventProto event1 = buildWithOtherInfo(json);
    HiveHookEvents.HiveHookEventProto event2 = buildIn2Steps(json, null);
    assertArrayEquals(event1.toByteArray(), event2.toByteArray());
  }

  @Test
  public void testOtherInfoQueryPlan() {
    HiveHookEvents.HiveHookEventProto event = buildIn2Steps(new JSONObject(), createExplainWork());
    Set<String> expectedOutput = Set.of("CONF", "QUERY");
    Set<String> actualOutput =
        event.getOtherInfoList().stream()
            .map(HiveHookEvents.MapFieldEntry::getKey)
            .collect(Collectors.toSet());
    assertEquals(expectedOutput, actualOutput);
  }

  private HiveHookEvents.HiveHookEventProto buildWithOtherInfo(JSONObject json) {
    return HiveHookEvents.HiveHookEventProto
            .newBuilder()
            .setEventType(HiveProtoLoggingHook.EventType.QUERY_SUBMITTED.name())
            .setTimestamp(TIMESTAMP)
            .setHiveQueryId(QUERY_1)
            .setUser(HIVE)
            .setRequestUser(HIVE)
            .setQueue(LLAP)
            .setExecutionMode(TEZ)
            .addAllOtherInfo(singletonList(HiveHookEvents.MapFieldEntry.newBuilder()
                    .setKey(HiveProtoLoggingHook.OtherInfoType.CONF.name())
                    .setValue(json.toString()).build()))
            .build();
  }

  private HiveHookEvents.HiveHookEventProto buildIn2Steps(JSONObject json, ExplainWork work) {
    HiveHookEvents.HiveHookEventProto.Builder builder = HiveHookEvents.HiveHookEventProto
            .newBuilder()
            .setEventType(HiveProtoLoggingHook.EventType.QUERY_SUBMITTED.name())
            .setTimestamp(TIMESTAMP)
            .setHiveQueryId(QUERY_1)
            .setUser(HIVE)
            .setRequestUser(HIVE)
            .setQueue(LLAP)
            .setExecutionMode(TEZ);
    Map<HiveProtoLoggingHook.OtherInfoType, JSONObject> otherInfo = new HashMap<>();
    otherInfo.put(HiveProtoLoggingHook.OtherInfoType.CONF, json);
    return new HiveHookEventProtoPartialBuilder(
            builder, work, otherInfo, null, HiveConf.ConfVars.HIVE_STAGE_ID_REARRANGE.defaultStrVal)
        .build();
  }

  private static ExplainWork createExplainWork() {
    CompilationOpContext cCtx = new CompilationOpContext();
    TableScanOperator scanOp = new TableScanOperator(cCtx);

    FetchWork taskWork = new FetchWork(new Path("mock"), new TableDesc());
    taskWork.setSource(scanOp);

    FetchTask task = new FetchTask();
    task.setWork(taskWork);

    ExplainWork work = new ExplainWork();
    work.setConfig(new ExplainConfiguration());
    work.setRootTasks(List.of(task));

    return work;
  }
}
