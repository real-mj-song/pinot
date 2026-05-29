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
 * Regression tests for {@link StringColumnPreIndexStatsCollector} input tolerance.
 *
 * <p>The collect path previously cast entries to {@link String} unconditionally
 * ({@code String value = (String) entry}). That contract is satisfied by Pinot's own
 * {@code PinotSegmentColumnReaderImpl}, which decodes string columns into
 * {@code java.lang.String} directly. Other {@code ColumnReaderFactory} implementations
 * (e.g. one wrapping Apache Arrow's {@code VarCharVector}) can legitimately produce
 * non-String text wrappers — Arrow's {@code org.apache.arrow.vector.util.Text} is one
 * concrete example. In the column-major build path, such values reach this collector via
 * {@code ColumnarSegmentPreIndexStatsContainer.collect()} and the unconditional cast
 * throws {@link ClassCastException} at runtime, failing the entire segment build.
 *
 * <p>These tests pin the relaxed contract: {@code collect(Object)} accepts any object whose
 * {@code toString()} produces the desired string content. They exercise both the
 * single-value and multi-value paths.
 */
public class StringColumnPreIndexStatsCollectorTest {

  private static final String COLUMN_NAME = "stringCol";
  private static final String TABLE_NAME = "testTable";

  @Test
  public void testCollectSingleValueAcceptsNonStringTextWrapper() {
    StringColumnPreIndexStatsCollector collector = newCollector(true /* singleValue */);

    collector.collect(new NonStringText("alpha"));
    collector.collect("beta");
    collector.collect(new NonStringText("gamma"));
    collector.seal();

    assertEquals(collector.getCardinality(), 3);
    assertEquals(collector.getTotalNumberOfEntries(), 3);
    assertEquals((String) collector.getMinValue(), "alpha");
    assertEquals((String) collector.getMaxValue(), "gamma");
  }

  @Test
  public void testCollectMultiValueAcceptsNonStringTextWrapper() {
    StringColumnPreIndexStatsCollector collector = newCollector(false /* singleValue */);

    Object[] row1 = new Object[]{new NonStringText("alpha"), new NonStringText("beta")};
    Object[] row2 = new Object[]{"alpha", new NonStringText("delta")};

    collector.collect(row1);
    collector.collect(row2);
    collector.seal();

    assertEquals(collector.getCardinality(), 3);
    assertEquals(collector.getTotalNumberOfEntries(), 4);
    assertEquals(collector.getMaxNumberOfMultiValues(), 2);
  }

  private StringColumnPreIndexStatsCollector newCollector(boolean singleValue) {
    Schema schema = new Schema();
    schema.setSchemaName(TABLE_NAME);
    schema.addField(new DimensionFieldSpec(COLUMN_NAME, DataType.STRING, singleValue));
    TableConfig tableConfig =
        new TableConfigBuilder(TableType.OFFLINE).setTableName(TABLE_NAME).build();
    StatsCollectorConfig config = new StatsCollectorConfig(tableConfig, schema, null);
    return new StringColumnPreIndexStatsCollector(COLUMN_NAME, config);
  }

  /**
   * Minimal stand-in for any byte[]-backed text wrapper such as Arrow's
   * {@code org.apache.arrow.vector.util.Text}: a non-String Comparable whose
   * {@link #toString()} returns the encoded characters. Implementing Comparable
   * mirrors Arrow's Text (which is {@code Comparable<Text>}).
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
