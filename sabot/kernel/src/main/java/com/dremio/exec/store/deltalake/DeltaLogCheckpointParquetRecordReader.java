/*
 * Copyright (C) 2017-2019 Dremio Corporation
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
package com.dremio.exec.store.deltalake;

import static com.dremio.exec.store.deltalake.DeltaConstants.DELTA_FIELD_ADD;
import static com.dremio.exec.store.deltalake.DeltaConstants.SCHEMA_KEY_VALUE;
import static com.dremio.exec.store.deltalake.DeltaConstants.SCHEMA_PARTITION_VALUES;
import static com.dremio.exec.store.deltalake.DeltaConstants.SCHEMA_PARTITION_VALUES_PARSED;
import static com.dremio.exec.store.deltalake.DeltaConstants.SCHEMA_VALUE;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.ValueVector;
import org.apache.arrow.vector.complex.StructVector;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.util.JsonStringArrayList;
import org.apache.arrow.vector.util.JsonStringHashMap;
import org.apache.arrow.vector.util.TransferPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.common.AutoCloseables;
import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.common.expression.CastExpressionWithOverflow;
import com.dremio.common.expression.FieldReference;
import com.dremio.common.expression.FunctionCall;
import com.dremio.common.expression.FunctionCallFactory;
import com.dremio.common.expression.IfExpression;
import com.dremio.common.expression.LogicalExpression;
import com.dremio.common.expression.SchemaPath;
import com.dremio.common.logical.data.NamedExpression;
import com.dremio.common.types.TypeProtos;
import com.dremio.common.util.MajorTypeHelper;
import com.dremio.exec.record.BatchSchema;
import com.dremio.exec.store.RecordReader;
import com.dremio.exec.store.SampleMutator;
import com.dremio.exec.store.parquet.ParquetSubScan;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.op.project.SimpleProjector;
import com.dremio.sabot.op.scan.OutputMutator;
import com.dremio.sabot.op.scan.ScanOperator;
import com.google.common.base.Preconditions;

/**
 * Wrapper reader to read partition values
 */
public class DeltaLogCheckpointParquetRecordReader implements RecordReader {
  private static final Logger logger = LoggerFactory.getLogger(DeltaLogCheckpointParquetRecordReader.class);

  protected final RecordReader delegate;
  protected final OperatorContext context;
  protected SimpleProjector projector;
  private final List<Field> partitionCols;
  private SampleMutator innerReaderOutput;
  private final ParquetSubScan scanConfig;
  private final List<TransferPair> transferPairs = new ArrayList<>();
  private ScanOperator.ScanMutator outputMutator;
  private final Map<String, Integer> partitionColumnIndexMap = new HashMap<>();
  private final Set<String> partitionColNames;

  public DeltaLogCheckpointParquetRecordReader(OperatorContext context, RecordReader parquetReader,
                                               List<Field> partitionCols, ParquetSubScan parquetSubScan) {
    Preconditions.checkArgument(!partitionCols.isEmpty());
    this.context = context;
    this.delegate = parquetReader;
    this.partitionCols = partitionCols;
    this.scanConfig = parquetSubScan;
    this.partitionColNames = partitionCols.stream().map(Field::getName).collect(Collectors.toSet());
  }

  @Override
  public void setup(OutputMutator output) throws ExecutionSetupException {

    this.outputMutator = (ScanOperator.ScanMutator) output;

    // create and set up mutator
    this.innerReaderOutput = new SampleMutator(context.getAllocator());
    scanConfig.getFullSchema().maskAndReorder(scanConfig.getColumns()).materializeVectors(scanConfig.getColumns(), innerReaderOutput);
    innerReaderOutput.getContainer().buildSchema(BatchSchema.SelectionVectorMode.NONE);
    innerReaderOutput.getAndResetSchemaChanged();
    delegate.setup(innerReaderOutput);

    // setup transfer pairs for non-partition fields
    StructVector targetAddVector = (StructVector) output.getVector(DeltaConstants.DELTA_FIELD_ADD);
    StructVector srcAddVector = (StructVector) innerReaderOutput.getVector(DeltaConstants.DELTA_FIELD_ADD);
    srcAddVector.getChildrenFromFields()
      .stream()
      .filter(src -> !src.getName().equals(DeltaConstants.SCHEMA_PARTITION_VALUES_PARSED) &&
        !src.getName().equals(SCHEMA_PARTITION_VALUES))
      .forEach(src -> transferPairs.add(src.makeTransferPair(targetAddVector.getChild(src.getName()))));

  }

  private void createAndSetupProjector() {
    projector = new SimpleProjector(context, innerReaderOutput.getContainer(), this.exprsToReadPartitionValues(),
      outputMutator.getContainer());
    projector.setup();
  }

  /**
   * generates NVL(partitionValues_parsed.[partitionCol], cast(partitionValues.key_value[x].value))
   */
  private List<NamedExpression> exprsToReadPartitionValues() {
    List<NamedExpression> exprs = new ArrayList<>();

    for (Field field : partitionCols) {
      final SchemaPath partitionValue = // add.partitionValues.key_value[i]['value']
        SchemaPath.getCompoundPath(DELTA_FIELD_ADD, SCHEMA_PARTITION_VALUES, SCHEMA_KEY_VALUE)
          .getChild(partitionColumnIndexMap.get(field.getName()))
          .getChild(SCHEMA_VALUE);

      final SchemaPath parsedPartitionValue = // add.partitionValues_parsed.[columnName]
        SchemaPath.getCompoundPath(DELTA_FIELD_ADD, SCHEMA_PARTITION_VALUES_PARSED, field.getName());
      final FieldReference outputRef = FieldReference.getWithQuotedRef(field.getName()); // top level partition column field

      TypeProtos.MajorType targetType = MajorTypeHelper.getMajorTypeForField(field);

      LogicalExpression cast;
      if (targetType.getMinorType().equals(TypeProtos.MinorType.VARCHAR) || targetType.getMinorType().equals(TypeProtos.MinorType.VARBINARY)) {
        cast = partitionValue;
      } else if (targetType.getMinorType().equals(TypeProtos.MinorType.DECIMAL)) {
        cast = new CastExpressionWithOverflow(partitionValue, targetType);
      } else {
        cast = FunctionCallFactory.createCast(targetType, partitionValue);
      }

      IfExpression.IfCondition parsedPartitionValueIsNotNull = new IfExpression.IfCondition(
        new FunctionCall("isnotnull", Collections.singletonList(parsedPartitionValue)), parsedPartitionValue);

      IfExpression ifEx = IfExpression.newBuilder()
        .setIfCondition(parsedPartitionValueIsNotNull)
        .setElse(cast)
        .build();

      exprs.add(new NamedExpression(ifEx, outputRef));
    }
    return exprs;
  }

  @Override
  public int next() {
    int count = delegate.next();
    transferPairs.forEach(TransferPair::transfer);
    if (projector == null) {
      createPartitionColumnIndexMap(count);
      if (partitionColumnIndexMap.isEmpty()) {
        logger.debug("No valid record found in the delta batch for this checkpoint parquet.");
        return 0;
      }
      this.createAndSetupProjector();
    }
    projector.eval(count);
    return count;
  }

  private void createPartitionColumnIndexMap(int count) {
    final StructVector partitionValuesStruct = ((StructVector) innerReaderOutput.getVector(DELTA_FIELD_ADD))
      .addOrGetStruct(SCHEMA_PARTITION_VALUES);

    for (int i = 0; i < count; i++) {
      if (!partitionValuesStruct.isNull(i)) {
        final JsonStringHashMap partitionValues = (JsonStringHashMap) partitionValuesStruct.getObject(i);
        final JsonStringArrayList keyValuePairs = (JsonStringArrayList) partitionValues.get("key_value");
        for (int k = 0; k < keyValuePairs.size(); k++) {
          final JsonStringHashMap keyValuePair = (JsonStringHashMap) keyValuePairs.get(k);
          final String partColName = keyValuePair.get("key").toString();
          partitionColumnIndexMap.put(partColName, k);
        }
        if (partitionColumnIndexMap.keySet().equals(this.partitionColNames)) {
          break;
        } else {
          logger.info("Partition columns in the schema are different from the ones in the checkpoint [{}, {}]." +
                  " The table was repartitioned after the checkpoint, hence the checkpoint parquet read can be skipped.",
                  partitionColumnIndexMap.keySet(), partitionColNames);
          partitionColumnIndexMap.clear();
          return;
        }
      }
    }
  }

  @Override
  public void allocate(Map<String, ValueVector> vectorMap) throws OutOfMemoryException {
    for (final ValueVector v : vectorMap.values()) {
      v.allocateNew();
    }
    delegate.allocate(innerReaderOutput.getFieldVectorMap());
  }

  @Override
  public void close() throws Exception {
    AutoCloseables.close(delegate, innerReaderOutput, projector);
  }
}
