// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License.

package com.azure.cosmos.kafka.connect.sink;

import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.implementation.BadRequestException;
import com.azure.cosmos.kafka.connect.source.JsonToStruct;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.kafka.connect.data.ConnectSchema;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mockConstructionWithAnswer;
import static org.mockito.Mockito.when;

@RunWith(Parameterized.class)
public class CosmosDBSinkTaskTest {
    private final String topicName = "testtopic";
    private final String containerName = "container666";
    private final String databaseName = "fakeDatabase312";
    private final boolean isBulkModeEnabled;

    private CosmosDBSinkTask testTask;
    private CosmosClient mockCosmosClient;
    private CosmosContainer mockContainer;

    @Parameterized.Parameters
    public static Iterable<? extends Object> bulkModeEnabledProvider() {
        return Arrays.asList(true, false);
    }

    public CosmosDBSinkTaskTest(boolean bulkModeEnabled) {
        this.isBulkModeEnabled = bulkModeEnabled;
    }

    @Before
    public void setup() throws IllegalAccessException {
        testTask = new CosmosDBSinkTask();

        //Configure settings
        Map<String, String> settingAssignment = CosmosDBSinkConfigTest.setupConfigs();
        settingAssignment.put(CosmosDBSinkConfig.COSMOS_CONTAINER_TOPIC_MAP_CONF, topicName + "#" + containerName);
        settingAssignment.put(CosmosDBSinkConfig.COSMOS_DATABASE_NAME_CONF, databaseName);
        settingAssignment.put(CosmosDBSinkConfig.COSMOS_SINK_BULK_ENABLED_CONF, String.valueOf(this.isBulkModeEnabled));
        CosmosDBSinkConfig config = new CosmosDBSinkConfig(settingAssignment);
        FieldUtils.writeField(testTask, "config", config, true);

        //Mock the Cosmos SDK
        mockCosmosClient = Mockito.mock(CosmosClient.class);
        CosmosDatabase mockDatabase = Mockito.mock(CosmosDatabase.class);
        when(mockCosmosClient.getDatabase(anyString())).thenReturn(mockDatabase);
        mockContainer = Mockito.mock(CosmosContainer.class);
        when(mockDatabase.getContainer(any())).thenReturn(mockContainer);

        FieldUtils.writeField(testTask, "client", mockCosmosClient, true);
    }

    @Test
    public void sinkWriteFailed() {
        Schema stringSchema = new ConnectSchema(Schema.Type.STRING);

        SinkRecord record = new SinkRecord(topicName, 1, stringSchema, "nokey", stringSchema, "foo", 0L);
        assertNotNull(record.value());

        SinkWriteResponse sinkWriteResponse = new SinkWriteResponse();
        sinkWriteResponse
                .getFailedRecordResponses()
                .add(new SinkOperationFailedResponse(record, new BadRequestException("Unable to serialize JSON request")));

        MockedConstruction<? extends SinkWriterBase> mockedWriterConstruction = null;

        try {
            if (this.isBulkModeEnabled) {
                mockedWriterConstruction = mockConstructionWithAnswer(BulkWriter.class, invocation -> {
                    if (invocation.getMethod().equals(BulkWriter.class.getMethod("write", List.class))) {
                        return sinkWriteResponse;
                    }

                    throw new IllegalStateException("Not implemented for method " + invocation.getMethod().getName());
                });
            } else {
                mockedWriterConstruction = mockConstructionWithAnswer(PointWriter.class, invocation -> {
                    if (invocation.getMethod().equals(PointWriter.class.getMethod("write", List.class))) {
                        return sinkWriteResponse;
                    }

                    throw new IllegalStateException("Not implemented for method " + invocation.getMethod().getName());
                });
            }

            try {
                testTask.put(List.of(record));
                fail("Expected ConnectException on bad message");
            } catch (ConnectException ce) {

            } catch (Throwable t) {
                fail("Expected ConnectException, but got: " + t.getClass().getName());
            }

            assertEquals(1, mockedWriterConstruction.constructed().size());
        } finally {
            if (mockedWriterConstruction != null) {
                mockedWriterConstruction.close();
            }
        }
    }

    @Test
    public void sinkWriteSucceeded() {
        Schema stringSchema = new ConnectSchema(Schema.Type.STRING);
        Schema mapSchema = new ConnectSchema(Schema.Type.MAP);
        Map<String, String> map = new HashMap<>();
        map.put("foo", "baaarrrrrgh");

        SinkRecord record = new SinkRecord(topicName, 1, stringSchema, "nokey", mapSchema, map, 0L);
        assertNotNull(record.value());

        SinkWriteResponse sinkWriteResponse = new SinkWriteResponse();
        sinkWriteResponse.getSucceededRecords().add(record);

        MockedConstruction<? extends SinkWriterBase> mockedWriterConstruction = null;
        try {
            if (this.isBulkModeEnabled) {
                mockedWriterConstruction = mockConstructionWithAnswer(BulkWriter.class, invocation -> {
                    if (invocation.getMethod().equals(BulkWriter.class.getMethod("write", List.class))) {
                        return sinkWriteResponse;
                    }

                    throw new IllegalStateException("Not implemented for method " + invocation.getMethod().getName());
                });
            } else {
                mockedWriterConstruction = mockConstructionWithAnswer(PointWriter.class, invocation -> {
                    if (invocation.getMethod().equals(PointWriter.class.getMethod("write", List.class))) {
                        return sinkWriteResponse;
                    }

                    throw new IllegalStateException("Not implemented for method " + invocation.getMethod().getName());
                });
            }

            try {
                testTask.put(List.of(record));
            } catch (ConnectException ce) {
                fail("Expected sink write succeeded. but got: " + ce.getMessage());
            } catch (Throwable t) {
                fail("Expected sink write succeeded, but got: " + t.getClass().getName());
            }

            assertEquals(1, mockedWriterConstruction.constructed().size());
        } finally {
            if (mockedWriterConstruction != null) {
                mockedWriterConstruction.close();
            }
        }
    }

    @Test
    public void sinkWriteSucceededWithStructRecordValue() {
        Schema stringSchema = new ConnectSchema(Schema.Type.STRING);
        Schema structSchema = new ConnectSchema(Schema.Type.STRUCT);
        ObjectNode objectNode = new ObjectNode(new JsonNodeFactory(false))
            .put("foo", "fooz")
            .put("bar", "baaz");
        JsonToStruct jsonToStruct = new JsonToStruct();

        Object recordValue = jsonToStruct.recordToSchemaAndValue(objectNode).value();


        SinkRecord record = new SinkRecord(topicName, 1, stringSchema, "nokey", structSchema, recordValue, 0L);
        assertNotNull(record.value());

        SinkWriteResponse sinkWriteResponse = new SinkWriteResponse();
        sinkWriteResponse.getSucceededRecords().add(record);

        MockedConstruction<? extends SinkWriterBase> mockedWriterConstruction = null;
        AtomicReference<List<SinkRecord>> sinkRecords = new AtomicReference<>();
        try {
            if (this.isBulkModeEnabled) {
                mockedWriterConstruction = mockConstructionWithAnswer(BulkWriter.class, invocation -> {
                    if (invocation.getMethod().equals(BulkWriter.class.getMethod("write", List.class))) {
                        sinkRecords.set(invocation.getArgument(0));
                        return sinkWriteResponse;
                    }

                    throw new IllegalStateException("Not implemented for method " + invocation.getMethod().getName());
                });
            } else {
                mockedWriterConstruction = mockConstructionWithAnswer(PointWriter.class, invocation -> {
                    if (invocation.getMethod().equals(PointWriter.class.getMethod("write", List.class))) {
                        sinkRecords.set(invocation.getArgument(0));
                        return sinkWriteResponse;
                    }

                    throw new IllegalStateException("Not implemented for method " + invocation.getMethod().getName());
                });
            }

            try {
                testTask.put(List.of(record));
            } catch (ConnectException ce) {
                fail("Expected sink write succeeded. but got: " + ce.getMessage());
            } catch (Throwable t) {
                fail("Expected sink write succeeded, but got: " + t.getClass().getName());
            }

            assertEquals(1, mockedWriterConstruction.constructed().size());

            SinkRecord sinkRecord = sinkRecords.get().get(0);
            assertRecordEquals(record, sinkRecord);

            Object value = sinkRecord.value();
            assertTrue(value instanceof Map);

            assertEquals("fooz", ((Map<?, ?>) value).get("foo"));
            assertEquals("baaz", ((Map<?, ?>) value).get("bar"));
        } finally {
            if (mockedWriterConstruction != null) {
                mockedWriterConstruction.close();
            }
        }
    }

    @Test
    public void sinkWriteSucceededWithMapRecordValue() {
        Schema stringSchema = new ConnectSchema(Schema.Type.STRING);
        Schema mapSchema = new ConnectSchema(Schema.Type.MAP);
        Map<String, String> map = new HashMap<>();
        map.put("foo", "fooz");
        map.put("bar", "baaz");


        SinkRecord record = new SinkRecord(topicName, 1, stringSchema, "nokey", mapSchema, map, 0L);
        assertNotNull(record.value());

        SinkWriteResponse sinkWriteResponse = new SinkWriteResponse();
        sinkWriteResponse.getSucceededRecords().add(record);

        MockedConstruction<? extends SinkWriterBase> mockedWriterConstruction = null;
        AtomicReference<List<SinkRecord>> sinkRecords = new AtomicReference<>();
        try {
            if (this.isBulkModeEnabled) {
                mockedWriterConstruction = mockConstructionWithAnswer(BulkWriter.class, invocation -> {
                    if (invocation.getMethod().equals(BulkWriter.class.getMethod("write", List.class))) {
                        sinkRecords.set(invocation.getArgument(0));
                        return sinkWriteResponse;
                    }

                    throw new IllegalStateException("Not implemented for method " + invocation.getMethod().getName());
                });
            } else {
                mockedWriterConstruction = mockConstructionWithAnswer(PointWriter.class, invocation -> {
                    if (invocation.getMethod().equals(PointWriter.class.getMethod("write", List.class))) {
                        sinkRecords.set(invocation.getArgument(0));
                        return sinkWriteResponse;
                    }

                    throw new IllegalStateException("Not implemented for method " + invocation.getMethod().getName());
                });
            }

            try {
                testTask.put(List.of(record));
            } catch (ConnectException ce) {
                fail("Expected sink write succeeded. but got: " + ce.getMessage());
            } catch (Throwable t) {
                fail("Expected sink write succeeded, but got: " + t.getClass().getName());
            }

            assertEquals(1, mockedWriterConstruction.constructed().size());

            SinkRecord sinkRecord = sinkRecords.get().get(0);
            assertRecordEquals(record, sinkRecord);

            Object value = sinkRecord.value();
            assertTrue(value instanceof Map);

            assertEquals("fooz", ((Map<?, ?>) value).get("foo"));
            assertEquals("baaz", ((Map<?, ?>) value).get("bar"));
        } finally {
            if (mockedWriterConstruction != null) {
                mockedWriterConstruction.close();
            }
        }
    }

    private void assertRecordEquals(SinkRecord record, SinkRecord updatedRecord) {
        assertEquals(record.kafkaOffset(), updatedRecord.kafkaOffset());
        assertEquals(record.timestamp(), updatedRecord.timestamp());
        assertEquals(record.timestampType(), updatedRecord.timestampType());
        assertEquals(record.headers(), updatedRecord.headers());
        assertEquals(record.keySchema(), updatedRecord.keySchema());
        assertEquals(record.valueSchema(), updatedRecord.valueSchema());
        assertEquals(record.key(), updatedRecord.key());
        assertEquals(record.topic(), updatedRecord.topic());
        assertEquals(record.kafkaPartition(), updatedRecord.kafkaPartition());
    }
}

