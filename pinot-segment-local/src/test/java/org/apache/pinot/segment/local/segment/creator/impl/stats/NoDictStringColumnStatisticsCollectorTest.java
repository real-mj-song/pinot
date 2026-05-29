/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.segment.local.segment.creator.impl.stats;

import org.apache.pinot.segment.spi.creator.StatsCollectorConfig;
import org.apache.pinot.spi.config.table.TableConfig;
import org.apache.pinot.spi.config.table.TableType;
import org.apache.pinot.spi.data.DimensionFieldSpec;
import org.apache.pinot.spi.data.FieldSpec.DataType;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.utils.builder.TableConfigBuilder;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;


/**
 * Regression tests for {@link NoDictColumnStatisticsCollector} string-typed input tolerance.
 *
 * <p>The collector previously cast string values to {@link String} unconditionally in three
 * places — the {@code _isAscii} length checks in both the single-value and multi-value paths,
 * and inside {@code getValueLength()} for the {@code STRING} case. That contract was
 * satisfied by Pinot's own {@code PinotSegmentColumnReaderImpl}, which decodes string columns
 * into {@code java.lang.String} directly. Other {@code ColumnReaderFactory} implementations
 * (e.g. one wrapping Apache Arrow's {@code VarCharVector}) can legitimately produce non-String
 * text wrappers — Arrow's {@code org.apache.arrow.vector.util.Text} is one concrete example.
 *
 * <p>In the column-major build path, such values reach this collector for no-dictionary
 * string columns via {@code ColumnarSegmentPreIndexStatsContainer.collect()} and the
 * unconditional cast throws {@link ClassCastException} at runtime.
 *
 * <p>These tests pin the relaxed contract: {@code collect(Object)} accepts any object whose
 * {@code toString()} produces the desired string content, in both single-value and
 * multi-value modes.
 */
public class NoDictStringColumnStatisticsCollectorTest {

  private static final String COLUMN_NAME = "stringCol";
  private static final String TABLE_NAME = "testTable";

  @Test
  public void testCollectSingleValueAcceptsNonStringTextWrapper() {
    NoDictColumnStatisticsCollector collector = newCollector(true /* singleValue */);

    // All values are NonStringText. Real Arrow column readers emit a single concrete
    // value type per column; this collector's min/max tracking uses Comparable.compareTo
    // which requires same-type values within a column.
    collector.collect(new NonStringText("alpha"));
    collector.collect(new NonStringText("beta"));
    collector.collect(new NonStringText("gamma"));
    collector.seal();

    assertEquals(collector.getCardinality(), 3);
    assertEquals(collector.getTotalNumberOfEntries(), 3);
  }

  @Test
  public void testCollectMultiValueAcceptsNonStringTextWrapper() {
    NoDictColumnStatisticsCollector collector = newCollector(false /* singleValue */);

    Object[] row1 = new Object[]{new NonStringText("alpha"), new NonStringText("beta")};
    Object[] row2 = new Object[]{new NonStringText("alpha"), new NonStringText("delta")};

    collector.collect(row1);
    collector.collect(row2);
    collector.seal();

    assertEquals(collector.getCardinality(), 3);
    assertEquals(collector.getTotalNumberOfEntries(), 4);
    assertEquals(collector.getMaxNumberOfMultiValues(), 2);
  }

  private NoDictColumnStatisticsCollector newCollector(boolean singleValue) {
    Schema schema = new Schema();
    schema.setSchemaName(TABLE_NAME);
    schema.addField(new DimensionFieldSpec(COLUMN_NAME, DataType.STRING, singleValue));
    TableConfig tableConfig =
        new TableConfigBuilder(TableType.OFFLINE).setTableName(TABLE_NAME).build();
    StatsCollectorConfig config = new StatsCollectorConfig(tableConfig, schema, null);
    return new NoDictColumnStatisticsCollector(COLUMN_NAME, config);
  }

  /**
   * Minimal stand-in for any byte[]-backed text wrapper such as Arrow's
   * {@code org.apache.arrow.vector.util.Text}: a non-String Comparable whose
   * {@link #toString()} returns the encoded characters. Implementing Comparable
   * mirrors Arrow's Text (which is {@code Comparable<Text>}) so that the wrapper
   * survives the {@code toComparable()} check inside
   * {@link NoDictColumnStatisticsCollector} on the way to the downstream string
   * paths under test.
   */
  private static final class NonStringText implements Comparable<NonStringText> {
    private final String _value;

    NonStringText(String value) {
      _value = value;
    }

    @Override
    public int compareTo(NonStringText other) {
      return _value.compareTo(other._value);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof NonStringText)) {
        return false;
      }
      return _value.equals(((NonStringText) o)._value);
    }

    @Override
    public int hashCode() {
      return _value.hashCode();
    }

    @Override
    public String toString() {
      return _value;
    }
  }
}
