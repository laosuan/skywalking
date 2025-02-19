/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.apache.skywalking.oap.server.core.profiling.asyncprofiler;

import lombok.RequiredArgsConstructor;
import org.apache.skywalking.oap.server.core.Const;
import org.apache.skywalking.oap.server.core.analysis.TimeBucket;
import org.apache.skywalking.oap.server.core.analysis.worker.NoneStreamProcessor;
import org.apache.skywalking.oap.server.core.profiling.asyncprofiler.storage.AsyncProfilerTaskRecord;
import org.apache.skywalking.oap.server.core.query.type.AsyncProfilerEventType;
import org.apache.skywalking.oap.server.core.query.type.AsyncProfilerTask;
import org.apache.skywalking.oap.server.core.query.type.AsyncProfilerTaskCreationResult;
import org.apache.skywalking.oap.server.core.query.type.AsyncProfilerTaskCreationType;
import org.apache.skywalking.oap.server.core.storage.StorageModule;
import org.apache.skywalking.oap.server.core.storage.profiling.asyncprofiler.IAsyncProfilerTaskQueryDAO;
import org.apache.skywalking.oap.server.library.module.ModuleManager;
import org.apache.skywalking.oap.server.library.module.Service;
import org.apache.skywalking.oap.server.library.util.CollectionUtils;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class AsyncProfilerMutationService implements Service {
    private final ModuleManager moduleManager;

    private IAsyncProfilerTaskQueryDAO taskQueryDAO;

    private IAsyncProfilerTaskQueryDAO getAsyncProfileTaskDAO() {
        if (taskQueryDAO == null) {
            this.taskQueryDAO = moduleManager.find(StorageModule.NAME)
                    .provider()
                    .getService(IAsyncProfilerTaskQueryDAO.class);
        }
        return taskQueryDAO;
    }

    public AsyncProfilerTaskCreationResult createTask(String serviceId,
                                                      List<String> serviceInstanceIds,
                                                      int duration,
                                                      List<AsyncProfilerEventType> events,
                                                      String execArgs) throws IOException {
        long createTime = System.currentTimeMillis();
        // check data
        AsyncProfilerTaskCreationResult checkResult = checkDataSuccess(
                serviceId, serviceInstanceIds, duration, createTime, events
        );
        if (checkResult != null) {
            return checkResult;
        }

        // create task
        AsyncProfilerTaskRecord task = new AsyncProfilerTaskRecord();
        task.setTaskId(createTime + Const.ID_CONNECTOR + serviceId);
        task.setServiceId(serviceId);
        task.setServiceInstanceIdsFromList(serviceInstanceIds);
        task.setDuration(duration);
        List<String> rowEvents = events.stream().map(AsyncProfilerEventType::toString).collect(Collectors.toList());
        task.setEventsFromList(rowEvents);
        task.setCreateTime(createTime);
        task.setExecArgs(execArgs);
        task.setTimeBucket(TimeBucket.getRecordTimeBucket(createTime));
        NoneStreamProcessor.getInstance().in(task);

        return AsyncProfilerTaskCreationResult.builder()
                .id(task.id().build())
                .code(AsyncProfilerTaskCreationType.SUCCESS)
                .build();
    }

    private AsyncProfilerTaskCreationResult checkDataSuccess(String serviceId,
                                                             List<String> serviceInstanceIds,
                                                             int duration,
                                                             long createTime,
                                                             List<AsyncProfilerEventType> events) throws IOException {
        String checkArgumentMessage = checkArgumentError(serviceId, serviceInstanceIds, duration, events);
        if (checkArgumentMessage != null) {
            return AsyncProfilerTaskCreationResult.builder()
                    .code(AsyncProfilerTaskCreationType.ARGUMENT_ERROR)
                    .errorReason(checkArgumentMessage)
                    .build();
        }
        String checkTaskProfilingMessage = checkTaskProfiling(serviceId, createTime);
        if (checkTaskProfilingMessage != null) {
            return AsyncProfilerTaskCreationResult.builder()
                    .code(AsyncProfilerTaskCreationType.ALREADY_PROFILING_ERROR)
                    .errorReason(checkTaskProfilingMessage)
                    .build();
        }
        return null;
    }

    private String checkArgumentError(String serviceId,
                                      List<String> serviceInstanceIds,
                                      int duration,
                                      List<AsyncProfilerEventType> events) {
        if (serviceId == null) {
            return "service cannot be null";
        }
        if (duration <= 0) {
            return "duration cannot be negative";
        }
        if (CollectionUtils.isEmpty(events)) {
            return "events cannot be empty";
        }
        if (CollectionUtils.isEmpty(serviceInstanceIds)) {
            return "serviceInstanceIds cannot be empty";
        }
        return null;
    }

    private String checkTaskProfiling(String serviceId,
                                      long createTime) throws IOException {
        // Each service can only enable one task at a time
        long endTimeBucket = TimeBucket.getMinuteTimeBucket(createTime);
        final List<AsyncProfilerTask> alreadyHaveTaskList = getAsyncProfileTaskDAO().getTaskList(
                serviceId, null, endTimeBucket, 1
        );
        if (CollectionUtils.isNotEmpty(alreadyHaveTaskList)) {
            for (AsyncProfilerTask task : alreadyHaveTaskList) {
                if (task.getCreateTime() + TimeUnit.SECONDS.toMillis(task.getDuration()) >= createTime) {
                    // if the endTime is greater or equal than the startTime of the newly created task, i.e. there is overlap between two tasks, it is an invalid case
                    return "current service already has monitor async profiler task execute at this time";
                }
            }
        }
        return null;
    }
}
