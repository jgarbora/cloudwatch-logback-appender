/*
 * Copyright 2018  Dieter Bogdoll
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.dibog;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggerContextVO;
import ch.qos.logback.core.Layout;
import ch.qos.logback.core.spi.ContextAware;
import com.amazonaws.services.logs.AWSLogs;
import com.amazonaws.services.logs.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import static java.util.Objects.requireNonNull;

class AwsCWEventDump implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(AwsCWEventDump.class);

    private final RingBuffer<ILoggingEvent> queue;
    private final LoggingEventToString layout;
    private final AwsConfig awsConfig;
    private final boolean createLogGroup;
    private final String groupName;
    private final String streamName;
    private final DateFormat dateFormat;
    private final ContextAware logContext;
    private final Date dateHolder = new Date();
    private final PutLogEventsRequest logEventReq;

    private volatile boolean done = false;

    private AWSLogs awsLogs;
    private String currentStreamName = null;
    private String nextToken = null;

    public AwsCWEventDump( AwsLogAppender aAppender ) {
        logContext = requireNonNull(aAppender, "appender");
        awsConfig = aAppender.awsConfig==null ? new AwsConfig(): aAppender.awsConfig;
        queue = new RingBuffer<ILoggingEvent>(aAppender.queueLength);
        createLogGroup = aAppender.createLogGroup;
        groupName = requireNonNull(aAppender.groupName, "appender.groupName");
        logEventReq = new PutLogEventsRequest().withLogGroupName(groupName);
        streamName = requireNonNull(aAppender.streamName, "appender.streamName");

        if(aAppender.layout==null) {
            layout = new LoggingEventToStringImpl();
        }
        else {
            final Layout<ILoggingEvent> delegate = aAppender.layout;
            layout = new LoggingEventToString() {
                @Override
                public String map(ILoggingEvent event) {
                    return delegate.doLayout(event);
                }
            };
        }

        if(aAppender.dateFormat==null || aAppender.dateFormat.trim().isEmpty()) {
            dateFormat = null;
        }
        else {
            dateFormat = new SimpleDateFormat(aAppender.dateFormat);
        }
    }

    private void closeStream() {
        currentStreamName = null;
    }

    private void openStream(String aNewStreamName) {

        if(awsLogs==null) {
            try {
                awsLogs = awsConfig.createAWSLogs();
            }
            catch(Exception e) {
                logContext.addError("Exception while opening AWSLogs. Shutting down the cloud watch logger.", e);
                shutdown();
            }
        }

        if(createLogGroup) {
            if (findLogGroup(groupName)==null) {
                logContext.addInfo("creating log group '"+groupName+"'");
                try {
                    awsLogs.createLogGroup(new CreateLogGroupRequest(groupName));
                }
                catch(OperationAbortedException e) {
                    logContext.addError("couldn't create log group '"+groupName+"': "+e.getLocalizedMessage());
                }
            }
        }

        LogStream stream = findLogStream(groupName, aNewStreamName);
        if(stream==null) {
            try {
                logContext.addInfo("creating log stream '"+streamName+"'");
                awsLogs.createLogStream(new CreateLogStreamRequest(groupName, aNewStreamName));
            }
            catch(Exception e) {
                logContext.addError("Exception while creating log stream ( "+groupName+" / "+aNewStreamName+" ). Shutting down the cloud watch logger.", e);
                shutdown();
            }
            nextToken = null;
        }
        else {
            nextToken = stream.getUploadSequenceToken();
        }

        logEventReq.withLogStreamName(aNewStreamName);
        currentStreamName = aNewStreamName;

    }

    private LogGroup findLogGroup(String aName) {
        DescribeLogGroupsResult result = awsLogs.describeLogGroups(
                new DescribeLogGroupsRequest()
                        .withLogGroupNamePrefix(groupName)
        );
        for (LogGroup group : result.getLogGroups()) {
            if (group.getLogGroupName().equals(aName)) {
                return group;
            }
        }
        return null;
    }

    private LogStream findLogStream(String aGroupName, String aStreamName) {
        try {
            DescribeLogStreamsResult result = awsLogs.describeLogStreams(
                    new DescribeLogStreamsRequest(groupName)
                            .withLogStreamNamePrefix(aStreamName)
            );
            for (LogStream stream : result.getLogStreams()) {
                if (stream.getLogStreamName().equals(aStreamName)) {
                    return stream;
                }
            }
        }
        catch(Exception e) {
            logContext.addError("Exception while trying to describe log stream ( "+aGroupName+"/"+aStreamName+" ).  Shutting down the cloud watch logger.", e);
            shutdown();
        }

        return null;
    }

    private void log(Collection<ILoggingEvent> aEvents) {

        if (dateFormat!=null) {

            dateHolder.setTime(System.currentTimeMillis()); // IF service run in UTC will work
            String newStreamName = String.format("%s-%s",streamName, dateFormat.format(dateHolder));

            if (!newStreamName.equals(currentStreamName)) {
                logContext.addInfo("stream name changed from '"+currentStreamName+"' to '"+newStreamName+"'");
                closeStream();
                openStream(newStreamName);
            }

        } else if (awsLogs==null) {
            closeStream();
            openStream(streamName);
        }

        Collection<InputLogEvent> events =  new ArrayList<>(aEvents.size());

        for (ILoggingEvent event : aEvents) {
            if (event.getLoggerContextVO() != null) {
                events.add(new InputLogEvent()
                        .withTimestamp(event.getTimeStamp())
                        .withMessage(layout.map(event)));
            }
        }

        try {
//            nextToken = awsLogs.putLogEvents(logEventReq
//                            .withSequenceToken(nextToken)
//                            .withLogEvents(events)).getNextSequenceToken();


            awsLogs.putLogEvents(logEventReq
                    .withLogGroupName(groupName)
                    .withLogStreamName(currentStreamName)
                    .withLogEvents(events));

        } catch (Exception e) {
            logContext.addError("Exception while adding log events.", e);
            LOG.error(e.getMessage(),e);
        }
    }

    public void shutdown() {
        done = true;
    }

    public void queue(ILoggingEvent event) {
        queue.put(event);
    }

    public void run() {
        List<ILoggingEvent> collections = new LinkedList<ILoggingEvent>();
        LoggerContextVO context = null;
        while(!done) {

            try {
                int[] nbs = queue.drainTo(collections);
                if(context==null && !collections.isEmpty()) {
                    context = collections.get(0).getLoggerContextVO();
                }

                int msgProcessed = nbs[0];
                int msgSkipped = nbs[1];
                if(context!=null && msgSkipped>0) {
                    collections.add(new SkippedEvent(msgSkipped, context));
                }
                log(collections);
                collections.clear();
            }
            catch(InterruptedException e) {
                // ignoring
            }
        }
    }
}

