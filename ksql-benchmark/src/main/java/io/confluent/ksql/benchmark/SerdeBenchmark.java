/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.ksql.benchmark;

import io.confluent.avro.random.generator.Generator;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.datagen.RowGenerator;
import io.confluent.ksql.logging.processing.ProcessingLogContext;
import io.confluent.ksql.schema.ksql.PersistenceSchema;
import io.confluent.ksql.serde.Format;
import io.confluent.ksql.serde.FormatInfo;
import io.confluent.ksql.serde.GenericRowSerDe;
import io.confluent.ksql.util.KsqlConfig;
import io.confluent.ksql.util.Pair;
import io.confluent.ksql.util.SchemaUtil;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.connect.data.ConnectSchema;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 *  Runs JMH microbenchmarks against KSQL serdes.
 *  See `ksql-benchmark/README.md` for more info, including benchmark results
 *  and how to run the benchmarks.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 10)
@Measurement(iterations = 3, time = 10)
@Threads(4)
@Fork(3)
public class SerdeBenchmark {

  private static final Path SCHEMA_DIR = Paths.get("schemas");
  private static final String SCHEMA_FILE_SUFFIX = ".avro";
  private static final String TOPIC_NAME = "serde_benchmark";

  @State(Scope.Thread)
  public static class SchemaAndGenericRowState {
    org.apache.kafka.connect.data.Schema schema;
    GenericRow row;

    @Param({"impressions", "metrics"})
    public String schemaName;

    @Setup(Level.Iteration)
    public void setUp() throws Exception {
      final Generator generator = new Generator(getSchemaStream(), new Random());

      // choose arbitrary key
      final String key = generator.schema().getFields().get(0).name();

      final RowGenerator rowGenerator = new RowGenerator(generator, key);

      final Pair<Struct, GenericRow> genericRowPair = rowGenerator.generateRow();
      row = genericRowPair.getRight();
      schema = rowGenerator.schema().valueSchema();
    }

    private InputStream getSchemaStream() {
      return SerdeBenchmark.class.getClassLoader().getResourceAsStream(
          SCHEMA_DIR.resolve(schemaName + SCHEMA_FILE_SUFFIX).toString());
    }
  }

  @State(Scope.Thread)
  public static class SerdeState {

    private static final org.apache.kafka.connect.data.Schema KEY_SCHEMA = SchemaBuilder.struct()
        .field(SchemaUtil.ROWKEY_NAME, org.apache.kafka.connect.data.Schema.OPTIONAL_STRING_SCHEMA)
        .build();

    Serializer<GenericRow> serializer;
    Deserializer<GenericRow> deserializer;
    GenericRow row;
    byte[] bytes;

    @Param({"JSON", "Avro"})
    public String serializationFormat;

    @Setup(Level.Iteration)
    public void setUp(final SchemaAndGenericRowState rowState) {
      final Serde<GenericRow> serde;
      switch (serializationFormat) {
        case "JSON":
          serde = getJsonSerde(rowState.schema);
          break;
        case "Avro":
          serde = getAvroSerde(rowState.schema);
          break;
        default:
          throw new RuntimeException("Invalid format: " + serializationFormat);
      }
      serializer = serde.serializer();
      deserializer = serde.deserializer();
      row = rowState.row;
      bytes = serializer.serialize(TOPIC_NAME, row);
    }

    private static Serde<GenericRow> getJsonSerde(
        final org.apache.kafka.connect.data.Schema schema) {
      final Serializer<GenericRow> serializer = getJsonSerdeHelper(schema).serializer();
      // KsqlJsonDeserializer requires schema field names to be uppercase
      final Deserializer<GenericRow> deserializer =
          getJsonSerdeHelper(convertFieldNamesToUppercase(schema)).deserializer();
      return Serdes.serdeFrom(serializer, deserializer);
    }

    private static org.apache.kafka.connect.data.Schema convertFieldNamesToUppercase(
        final org.apache.kafka.connect.data.Schema schema) {
      SchemaBuilder builder = SchemaBuilder.struct();
      for (final Field field : schema.fields()) {
        builder = builder.field(field.name().toUpperCase(), field.schema());
      }
      return builder.build();
    }

    private static Serde<GenericRow> getJsonSerdeHelper(
        final org.apache.kafka.connect.data.Schema schema
    ) {
      return getGenericRowSerde(
          FormatInfo.of(Format.JSON, Optional.empty()),
          schema,
          () -> null
      );
    }

    private static Serde<GenericRow> getAvroSerde(
        final org.apache.kafka.connect.data.Schema schema
    ) {
      final SchemaRegistryClient schemaRegistryClient = new MockSchemaRegistryClient();

      return getGenericRowSerde(
          FormatInfo.of(Format.AVRO, Optional.of("benchmarkSchema")),
          schema,
          () -> schemaRegistryClient
      );
    }

    private static Serde<GenericRow> getGenericRowSerde(
        final FormatInfo format,
        final org.apache.kafka.connect.data.Schema schema,
        final Supplier<SchemaRegistryClient> schemaRegistryClientFactory
    ) {
      return GenericRowSerDe.from(
          format,
          PersistenceSchema.from((ConnectSchema) schema, false),
          new KsqlConfig(Collections.emptyMap()),
          schemaRegistryClientFactory,
          "benchmark",
          ProcessingLogContext.create()
      );
    }
  }

  @Benchmark
  public byte[] serialize(final SerdeState serdeState) {
    return serdeState.serializer.serialize(TOPIC_NAME, serdeState.row);
  }

  @Benchmark
  public GenericRow deserialize(final SerdeState serdeState) {
    return serdeState.deserializer.deserialize(TOPIC_NAME, serdeState.bytes);
  }

  public static void main(final String[] args) throws RunnerException {
    final Options opt = new OptionsBuilder()
        .include(SerdeBenchmark.class.getSimpleName())
        .build();

    new Runner(opt).run();
  }
}
