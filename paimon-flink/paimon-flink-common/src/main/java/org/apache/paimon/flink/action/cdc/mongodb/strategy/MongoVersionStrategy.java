/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.paimon.flink.action.cdc.mongodb.strategy;

import org.apache.paimon.flink.action.cdc.ComputedColumn;
import org.apache.paimon.flink.action.cdc.mongodb.SchemaAcquisitionMode;
import org.apache.paimon.flink.sink.cdc.RichCdcMultiplexRecord;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DataTypes;
import org.apache.paimon.utils.JsonSerdeUtil;

import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.paimon.shade.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;

import com.jayway.jsonpath.JsonPath;
import org.apache.flink.configuration.Configuration;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.paimon.flink.action.cdc.CdcActionCommonUtils.mapKeyCaseConvert;
import static org.apache.paimon.flink.action.cdc.CdcActionCommonUtils.recordKeyDuplicateErrMsg;
import static org.apache.paimon.flink.action.cdc.mongodb.MongoDBActionUtils.FIELD_NAME;
import static org.apache.paimon.flink.action.cdc.mongodb.MongoDBActionUtils.PARSER_PATH;
import static org.apache.paimon.flink.action.cdc.mongodb.MongoDBActionUtils.START_MODE;

/** Interface for processing strategies tailored for different MongoDB versions. */
public interface MongoVersionStrategy {

    ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Extracts records from the provided JsonNode.
     *
     * @param root The root JsonNode containing the MongoDB record.
     * @return A list of RichCdcMultiplexRecord extracted from the root node.
     * @throws JsonProcessingException If there's an error during JSON processing.
     */
    List<RichCdcMultiplexRecord> extractRecords(JsonNode root) throws JsonProcessingException;

    /**
     * Extracts primary keys from the MongoDB record.
     *
     * @return A list of primary keys.
     */
    default List<String> extractPrimaryKeys() {
        return Collections.singletonList("_id");
    }

    default Map<String, String> extractRow(String record) {
        return JsonSerdeUtil.parseJsonMap(record, String.class);
    }

    /**
     * Determines the extraction mode and retrieves the row accordingly.
     *
     * @param jsonNode The JsonNode representing the MongoDB document.
     * @param paimonFieldTypes A map to store the field types.
     * @param caseSensitive Flag indicating if the extraction should be case-sensitive.
     * @param mongodbConfig Configuration for the MongoDB connection.
     * @return A map representing the extracted row.
     * @throws JsonProcessingException If there's an error during JSON processing.
     */
    default Map<String, String> getExtractRow(
            JsonNode jsonNode,
            LinkedHashMap<String, DataType> paimonFieldTypes,
            boolean caseSensitive,
            List<ComputedColumn> computedColumns,
            Configuration mongodbConfig)
            throws JsonProcessingException {
        SchemaAcquisitionMode mode =
                SchemaAcquisitionMode.valueOf(mongodbConfig.getString(START_MODE).toUpperCase());
        ObjectNode objectNode = (ObjectNode) OBJECT_MAPPER.readTree(jsonNode.asText());
        JsonNode document = objectNode.set("_id", objectNode.get("_id").get("$oid"));
        Map<String, String> row;
        switch (mode) {
            case SPECIFIED:
                row =
                        parseFieldsFromJsonRecord(
                                document.toString(),
                                mongodbConfig.getString(PARSER_PATH),
                                mongodbConfig.getString(FIELD_NAME),
                                computedColumns,
                                paimonFieldTypes);
                break;
            case DYNAMIC:
                row =
                        parseAndTypeJsonRow(
                                document.toString(),
                                paimonFieldTypes,
                                computedColumns,
                                caseSensitive);
                break;
            default:
                throw new RuntimeException("Unsupported extraction mode: " + mode);
        }
        return mapKeyCaseConvert(row, caseSensitive, recordKeyDuplicateErrMsg(row));
    }

    /** Parses and types a JSON row based on the given parameters. */
    default Map<String, String> parseAndTypeJsonRow(
            String evaluate,
            LinkedHashMap<String, DataType> paimonFieldTypes,
            List<ComputedColumn> computedColumns,
            boolean caseSensitive) {
        Map<String, String> parsedRow = JsonSerdeUtil.parseJsonMap(evaluate, String.class);
        return processParsedData(parsedRow, paimonFieldTypes, computedColumns, caseSensitive);
    }

    /** Parses fields from a JSON record based on the given parameters. */
    static Map<String, String> parseFieldsFromJsonRecord(
            String record,
            String fieldPaths,
            String fieldNames,
            List<ComputedColumn> computedColumns,
            LinkedHashMap<String, DataType> fieldTypes) {
        String[] columnNames = fieldNames.split(",");
        String[] parseNames = fieldPaths.split(",");
        Map<String, String> parsedRow = new HashMap<>();

        for (int i = 0; i < parseNames.length; i++) {
            String evaluate = JsonPath.read(record, parseNames[i]);
            parsedRow.put(columnNames[i], Optional.ofNullable(evaluate).orElse("{}"));
        }

        return processParsedData(parsedRow, fieldTypes, computedColumns, false);
    }

    /** Processes the parsed data to generate the result map and update field types. */
    static Map<String, String> processParsedData(
            Map<String, String> parsedRow,
            LinkedHashMap<String, DataType> fieldTypes,
            List<ComputedColumn> computedColumns,
            boolean caseSensitive) {
        int initialCapacity = parsedRow.size() + computedColumns.size();
        Map<String, String> resultMap = new HashMap<>(initialCapacity);

        parsedRow.forEach(
                (column, value) -> {
                    String key = caseSensitive ? column : column.toLowerCase();
                    fieldTypes.putIfAbsent(key, DataTypes.STRING());
                    resultMap.put(key, value);
                });
        computedColumns.forEach(
                computedColumn -> {
                    String columnName = computedColumn.columnName();
                    String fieldReference = computedColumn.fieldReference();
                    String computedValue = computedColumn.eval(parsedRow.get(fieldReference));

                    resultMap.put(columnName, computedValue);
                    fieldTypes.put(columnName, computedColumn.columnType());
                });
        return resultMap;
    }
}
