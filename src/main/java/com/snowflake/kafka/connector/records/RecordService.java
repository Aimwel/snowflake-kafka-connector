/*
 * Copyright (c) 2019 Snowflake Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.snowflake.kafka.connector.records;

import static com.snowflake.kafka.connector.Utils.TABLE_COLUMN_CONTENT;
import static com.snowflake.kafka.connector.Utils.TABLE_COLUMN_METADATA;

import com.snowflake.kafka.connector.SnowflakeSinkConnectorConfig;
import com.snowflake.kafka.connector.internal.EnableLogging;
import com.snowflake.kafka.connector.internal.LoggerHandler;
import com.snowflake.kafka.connector.internal.SnowflakeErrors;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.core.JsonProcessingException;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.JsonNode;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.node.ArrayNode;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.node.JsonNodeFactory;
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.node.ObjectNode;
import net.snowflake.ingest.internal.apache.arrow.util.VisibleForTesting;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.ConnectSchema;
import org.apache.kafka.connect.data.Date;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.data.Time;
import org.apache.kafka.connect.data.Timestamp;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.sink.SinkRecord;

public class RecordService extends EnableLogging {
  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final LoggerHandler LOGGER = new LoggerHandler(RecordService.class.getName());

  // deleted private to use these values in test
  static final String OFFSET = "offset";
  static final String TOPIC = "topic";
  static final String PARTITION = "partition";
  static final String KEY = "key";
  static final String CONTENT = "content";
  static final String META = "meta";
  static final String SCHEMA_ID = "schema_id";
  private static final String KEY_SCHEMA_ID = "key_schema_id";
  static final String HEADERS = "headers";

  private boolean enableSchematization = false;

  // For each task, we require a separate instance of SimpleDataFormat, since they are not
  // inherently thread safe
  static final ThreadLocal<SimpleDateFormat> ISO_DATE_TIME_FORMAT =
      ThreadLocal.withInitial(
          () -> {
            SimpleDateFormat simpleDateFormat =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
            simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            return simpleDateFormat;
          });

  public static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
      ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSSZ"));
  static final int MAX_SNOWFLAKE_NUMBER_PRECISION = 38;

  // This class is designed to work with empty metadata config map
  private SnowflakeMetadataConfig metadataConfig = new SnowflakeMetadataConfig();

  /**
   * process records output JSON format: { "meta": { "offset": 123, "topic": "topic name",
   * "partition": 123, "key":"key name" } "content": "record content" }
   *
   * <p>create a JsonRecordService instance
   */
  public RecordService() {}

  public void setMetadataConfig(SnowflakeMetadataConfig metadataConfigIn) {
    metadataConfig = metadataConfigIn;
  }

  /**
   * extract enableSchematization from the connector config and set the value for the recordService
   *
   * <p>The extracted boolean is returned for external usage.
   *
   * @param connectorConfig the connector config map
   * @return a boolean indicating whether schematization is enabled
   */
  public boolean setAndGetEnableSchematizationFromConfig(
      final Map<String, String> connectorConfig) {
    if (connectorConfig.containsKey(SnowflakeSinkConnectorConfig.ENABLE_SCHEMATIZATION_CONFIG)) {
      this.enableSchematization =
          Boolean.parseBoolean(
              connectorConfig.get(SnowflakeSinkConnectorConfig.ENABLE_SCHEMATIZATION_CONFIG));
    }
    return this.enableSchematization;
  }

  /**
   * Directly set the enableSchematization through param
   *
   * <p>This method is only for testing
   *
   * @param enableSchematization whether we should enable schematization or not
   */
  @VisibleForTesting
  public void setEnableSchematization(final boolean enableSchematization) {
    this.enableSchematization = enableSchematization;
  }

  /**
   * process given SinkRecord, only support snowflake converters
   *
   * @param record SinkRecord
   * @return a Row wrapper which contains both actual content(payload) and metadata
   */
  private SnowflakeTableRow processRecord(SinkRecord record) {
    if (record.value() == null || record.valueSchema() == null) {
      throw SnowflakeErrors.ERROR_5016.getException();
    }
    if (!record.valueSchema().name().equals(SnowflakeJsonSchema.NAME)) {
      throw SnowflakeErrors.ERROR_0009.getException();
    }
    if (!(record.value() instanceof SnowflakeRecordContent)) {
      throw SnowflakeErrors.ERROR_0010.getException(
          "Input record should be SnowflakeRecordContent object");
    }

    SnowflakeRecordContent valueContent = (SnowflakeRecordContent) record.value();

    ObjectNode meta = MAPPER.createObjectNode();
    if (metadataConfig.topicFlag) {
      meta.put(TOPIC, record.topic());
    }
    if (metadataConfig.offsetAndPartitionFlag) {
      meta.put(OFFSET, record.kafkaOffset());
      meta.put(PARTITION, record.kafkaPartition());
    }

    // ignore if no timestamp
    if (record.timestampType() != TimestampType.NO_TIMESTAMP_TYPE
        && metadataConfig.createtimeFlag) {
      meta.put(record.timestampType().name, record.timestamp());
    }

    // include schema id if using avro with schema registry
    if (valueContent.getSchemaID() != SnowflakeRecordContent.NON_AVRO_SCHEMA) {
      meta.put(SCHEMA_ID, valueContent.getSchemaID());
    }

    putKey(record, meta);

    if (!record.headers().isEmpty()) {
      meta.set(HEADERS, parseHeaders(record.headers()));
    }

    return new SnowflakeTableRow(valueContent, meta);
  }

  /**
   * Given a single Record from put API, process it and convert it into a Json String.
   *
   * <p>Remember, Snowflake table has two columns, both of them are VARIANT columns whose contents
   * are in JSON
   *
   * @param record record from Kafka
   * @return Json String with metadata and actual Payload from Kafka Record
   */
  public String getProcessedRecordForSnowpipe(SinkRecord record) {
    SnowflakeTableRow row = processRecord(record);
    StringBuilder buffer = new StringBuilder();
    for (JsonNode node : row.content.getData()) {
      ObjectNode data = MAPPER.createObjectNode();
      data.set(CONTENT, node);
      if (metadataConfig.allFlag) {
        data.set(META, row.metadata);
      }
      buffer.append(data.toString());
    }
    return buffer.toString();
  }

  /**
   * Given a single Record from put API, process it and convert it into Map of String and Object.
   *
   * <p>This map contains two Keys and its corresponding values
   *
   * <p>Two keys are the column names and values are its contents.
   *
   * <p>Remember, Snowflake table has two columns, both of them are VARIANT columns whose contents
   * are in JSON
   *
   * <p>When schematization is enabled, the content of the record is extracted into a map
   *
   * @param record record from Kafka to (Which was serialized in Json)
   * @return Json String with metadata and actual Payload from Kafka Record
   */
  public Map<String, Object> getProcessedRecordForStreamingIngest(SinkRecord record)
      throws JsonProcessingException {
    SnowflakeTableRow row = processRecord(record);
    final Map<String, Object> streamingIngestRow = new HashMap<>();
    for (JsonNode node : row.content.getData()) {
      if (enableSchematization) {
        streamingIngestRow.putAll(getMapFromJsonNodeForStreamingIngest(node));
      } else {
        streamingIngestRow.put(TABLE_COLUMN_CONTENT, MAPPER.writeValueAsString(node));
      }
      if (metadataConfig.allFlag) {
        streamingIngestRow.put(TABLE_COLUMN_METADATA, MAPPER.writeValueAsString(row.metadata));
      }
    }

    return streamingIngestRow;
  }

  private Map<String, Object> getMapFromJsonNodeForStreamingIngest(JsonNode node)
      throws JsonProcessingException {
    final Map<String, Object> streamingIngestRow = new HashMap<>();
    Iterator<String> columnNames = node.fieldNames();
    while (columnNames.hasNext()) {
      String columnName = columnNames.next();
      JsonNode columnNode = node.get(columnName);
      Object columnValue;
      if (columnNode.isArray()) {
        List<String> itemList = new ArrayList<>();
        ArrayNode arrayNode = (ArrayNode) columnNode;
        for (JsonNode e : arrayNode) {
          itemList.add(e.isTextual() ? e.textValue() : MAPPER.writeValueAsString(e));
        }
        columnValue = itemList;
      } else if (columnNode.isTextual()) {
        columnValue = columnNode.textValue();
      } else if (columnNode.isNull()) {
        columnValue = null;
      } else {
        columnValue = MAPPER.writeValueAsString(columnNode);
      }
      // while the value is always dumped into a string, the Streaming Ingest SDK
      // will transform the value according to its type in the table
      streamingIngestRow.put(columnName, columnValue);
    }
    // Thrown an exception if the input JsonNode is not in the expected format
    if (streamingIngestRow.isEmpty()) {
      throw SnowflakeErrors.ERROR_0010.getException(
          "Not able to convert node to Snowpipe Streaming input format");
    }
    return streamingIngestRow;
  }

  /** For now there are two columns one is content and other is metadata. Both are Json */
  private static class SnowflakeTableRow {
    // This can be a JsonNode but we will keep this as is.
    private final SnowflakeRecordContent content;
    private final JsonNode metadata;

    public SnowflakeTableRow(SnowflakeRecordContent content, JsonNode metadata) {
      this.content = content;
      this.metadata = metadata;
    }
  }

  void putKey(SinkRecord record, ObjectNode meta) {
    if (record.key() == null) {
      return;
    }

    if (record.keySchema().toString().equals(Schema.STRING_SCHEMA.toString())) {
      meta.put(KEY, record.key().toString());
    } else if (SnowflakeJsonSchema.NAME.equals(record.keySchema().name())) {
      if (!(record.key() instanceof SnowflakeRecordContent)) {
        throw SnowflakeErrors.ERROR_0010.getException(
            "Input record key should be SnowflakeRecordContent object if key schema is"
                + " SNOWFLAKE_JSON_SCHEMA");
      }

      SnowflakeRecordContent keyContent = (SnowflakeRecordContent) record.key();

      JsonNode[] keyData = keyContent.getData();
      if (keyData.length == 1) {
        meta.set(KEY, keyData[0]);
      } else {
        ArrayNode keyNode = MAPPER.createArrayNode();
        keyNode.addAll(Arrays.asList(keyData));
        meta.set(KEY, keyNode);
      }

      if (keyContent.getSchemaID() != SnowflakeRecordContent.NON_AVRO_SCHEMA) {
        meta.put(KEY_SCHEMA_ID, keyContent.getSchemaID());
      }
    } else {
      throw SnowflakeErrors.ERROR_0010.getException(
          "Unsupported Key format, please implement either String Key Converter or Snowflake"
              + " Converters");
    }
  }

  static JsonNode parseHeaders(Headers headers) {
    ObjectNode result = MAPPER.createObjectNode();
    for (Header header : headers) {
      result.set(header.key(), convertToJson(header.schema(), header.value()));
    }
    return result;
  }

  /**
   * Convert this object, in the org.apache.kafka.connect.data format, into a JSON object, returning
   * the converted object.
   *
   * @param schema schema of the object
   * @param logicalValue object to be converted
   * @return a JsonNode of the object
   */
  public static JsonNode convertToJson(Schema schema, Object logicalValue) {
    if (logicalValue == null) {
      if (schema
          == null) // Any schema is valid and we don't have a default, so treat this as an optional
        // schema
        return null;
      if (schema.defaultValue() != null) return convertToJson(schema, schema.defaultValue());
      if (schema.isOptional()) return JsonNodeFactory.instance.nullNode();
      throw SnowflakeErrors.ERROR_5015.getException(
          "Conversion error: null value for field that is required and has no default value");
    }

    Object value = logicalValue;
    try {
      final Schema.Type schemaType;
      if (schema == null) {
        Schema.Type primitiveType = ConnectSchema.schemaType(value.getClass());
        if (primitiveType != null) {
          schemaType = primitiveType;
        } else {
          if (value instanceof java.util.Date) {
            schema = Timestamp.SCHEMA;
            schemaType = Schema.Type.INT64;
          } else {
            throw SnowflakeErrors.ERROR_5015.getException(
                "Java class " + value.getClass() + " does not have corresponding schema type.");
          }
        }
      } else {
        schemaType = schema.type();
      }
      switch (schemaType) {
        case INT8:
          return JsonNodeFactory.instance.numberNode((Byte) value);
        case INT16:
          return JsonNodeFactory.instance.numberNode((Short) value);
        case INT32:
          if (schema != null && Date.LOGICAL_NAME.equals(schema.name())) {
            return JsonNodeFactory.instance.textNode(
                ISO_DATE_TIME_FORMAT.get().format((java.util.Date) value));
          }
          if (schema != null && Time.LOGICAL_NAME.equals(schema.name())) {
            return JsonNodeFactory.instance.textNode(
                TIME_FORMAT.get().format((java.util.Date) value));
          }
          return JsonNodeFactory.instance.numberNode((Integer) value);
        case INT64:
          if (schema != null && Timestamp.LOGICAL_NAME.equals(schema.name())) {
            return JsonNodeFactory.instance.numberNode(
                Timestamp.fromLogical(schema, (java.util.Date) value));
          }
          return JsonNodeFactory.instance.numberNode((Long) value);
        case FLOAT32:
          return JsonNodeFactory.instance.numberNode((Float) value);
        case FLOAT64:
          return JsonNodeFactory.instance.numberNode((Double) value);
        case BOOLEAN:
          return JsonNodeFactory.instance.booleanNode((Boolean) value);
        case STRING:
          CharSequence charSeq = (CharSequence) value;
          return JsonNodeFactory.instance.textNode(charSeq.toString());
        case BYTES:
          if (schema != null && Decimal.LOGICAL_NAME.equals(schema.name())) {
            BigDecimal bigDecimalValue = (BigDecimal) value;
            if (bigDecimalValue.precision() > MAX_SNOWFLAKE_NUMBER_PRECISION) {
              // in order to prevent losing precision, convert this value to text
              return JsonNodeFactory.instance.textNode(bigDecimalValue.toString());
            }
            return JsonNodeFactory.instance.numberNode(bigDecimalValue);
          }

          byte[] valueArr = null;
          if (value instanceof byte[]) valueArr = (byte[]) value;
          else if (value instanceof ByteBuffer) {
            ByteBuffer byteBufferValue = (ByteBuffer) value;
            if (byteBufferValue.hasArray()) valueArr = ((ByteBuffer) value).array();
            else {
              // If the byte buffer is read only, make a copy of the buffer then access the byte
              // array.
              ByteBuffer clone = ByteBuffer.allocate(byteBufferValue.capacity());
              byteBufferValue.rewind();
              clone.put(byteBufferValue);
              byteBufferValue.rewind();
              clone.flip();
              valueArr = clone.array();
            }
          }

          if (valueArr == null)
            throw SnowflakeErrors.ERROR_5015.getException(
                "Invalid type for bytes type: " + value.getClass());

          return JsonNodeFactory.instance.binaryNode(valueArr);

        case ARRAY:
          {
            Collection collection = (Collection) value;
            ArrayNode list = JsonNodeFactory.instance.arrayNode();
            for (Object elem : collection) {
              Schema valueSchema = schema == null ? null : schema.valueSchema();
              JsonNode fieldValue = convertToJson(valueSchema, elem);
              list.add(fieldValue);
            }
            return list;
          }
        case MAP:
          {
            Map<?, ?> map = (Map<?, ?>) value;
            // If true, using string keys and JSON object; if false, using non-string keys and
            // Array-encoding
            boolean objectMode;
            if (schema == null) {
              objectMode = true;
              for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                  objectMode = false;
                  break;
                }
              }
            } else {
              objectMode =
                  (schema.keySchema() != null && schema.keySchema().type() == Schema.Type.STRING);
            }
            ObjectNode obj = null;
            ArrayNode list = null;
            if (objectMode) obj = JsonNodeFactory.instance.objectNode();
            else list = JsonNodeFactory.instance.arrayNode();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
              Schema keySchema = schema == null ? null : schema.keySchema();
              Schema valueSchema = schema == null ? null : schema.valueSchema();
              JsonNode mapKey = convertToJson(keySchema, entry.getKey());
              JsonNode mapValue = convertToJson(valueSchema, entry.getValue());

              if (objectMode) obj.set(mapKey.asText(), mapValue);
              else list.add(JsonNodeFactory.instance.arrayNode().add(mapKey).add(mapValue));
            }
            return objectMode ? obj : list;
          }
        case STRUCT:
          {
            Struct struct = (Struct) value;
            if (struct.schema() != schema)
              throw SnowflakeErrors.ERROR_5015.getException("Mismatching schema.");
            ObjectNode obj = JsonNodeFactory.instance.objectNode();
            for (Field field : schema.fields()) {
              obj.set(field.name(), convertToJson(field.schema(), struct.get(field)));
            }
            return obj;
          }
      }

      throw SnowflakeErrors.ERROR_5015.getException("Couldn't convert " + value + " to JSON.");
    } catch (ClassCastException e) {
      throw SnowflakeErrors.ERROR_5015.getException(
          "Invalid type for " + schema.type() + ": " + value.getClass());
    }
  }

  /**
   * Returns true if we want to skip this record since the value is null or it is an empty json
   * string.
   *
   * <p>Remember, we need to check what is the value schema. Depending on the value schema, we need
   * to find out if the value is null or empty JSON. It can be empty JSON string in case of custom
   * snowflake converters.
   *
   * <p>If the value is an empty JSON node, we could assume the value passed was null.
   *
   * @param record record sent from Kafka to KC
   * @param behaviorOnNullValues behavior passed inside KC
   * @return true if we would skip adding it to buffer
   * @see com.snowflake.kafka.connector.records.SnowflakeJsonConverter#toConnectData when bytes ==
   *     null case
   */
  public boolean shouldSkipNullValue(
      SinkRecord record,
      final SnowflakeSinkConnectorConfig.BehaviorOnNullValues behaviorOnNullValues) {
    if (behaviorOnNullValues == SnowflakeSinkConnectorConfig.BehaviorOnNullValues.DEFAULT) {
      return false;
    } else {
      boolean isRecordValueNull = false;
      // get valueSchema
      Schema valueSchema = record.valueSchema();
      if (valueSchema instanceof SnowflakeJsonSchema) {
        // we can conclude this is a custom/KC defined converter.
        // i.e one of SFJson, SFAvro and SFAvroWithSchemaRegistry Converter
        if (record.value() instanceof SnowflakeRecordContent) {
          SnowflakeRecordContent recordValueContent = (SnowflakeRecordContent) record.value();
          if (recordValueContent.isRecordContentValueNull()) {
            LOG_DEBUG_MSG(
                "Record value schema is:{} and value is Empty Json Node for topic {}, partition {}"
                    + " and offset {}",
                valueSchema.getClass().getName(),
                record.topic(),
                record.kafkaPartition(),
                record.kafkaOffset());
            isRecordValueNull = true;
          }
        }
      } else {
        // Else, it is one of the community converters.
        // Tombstone handler SMT can be used but we need to check here if value is null if SMT is
        // not used
        if (record.value() == null) {
          LOG_DEBUG_MSG(
              "Record value is null for topic {}, partition {} and offset {}",
              record.topic(),
              record.kafkaPartition(),
              record.kafkaOffset());
          isRecordValueNull = true;
        }
      }
      if (isRecordValueNull) {
        LOG_DEBUG_MSG(
            "Null valued record from topic '{}', partition {} and offset {} was skipped.",
            record.topic(),
            record.kafkaPartition(),
            record.kafkaOffset());
        return true;
      }
    }
    return false;
  }
}
