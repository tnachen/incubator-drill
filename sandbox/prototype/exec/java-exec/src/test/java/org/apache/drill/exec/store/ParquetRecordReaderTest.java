/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.apache.drill.exec.store;

import com.beust.jcommander.internal.Lists;
import mockit.Expectations;
import mockit.Injectable;
import org.apache.drill.common.exceptions.ExecutionSetupException;

import org.apache.drill.common.util.FileUtils;
import org.apache.drill.exec.exception.SchemaChangeException;
import org.apache.drill.exec.memory.DirectBufferAllocator;
import org.apache.drill.exec.ops.FragmentContext;
import org.apache.drill.exec.physical.impl.OutputMutator;
import org.apache.drill.exec.record.vector.ValueVector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.junit.Test;
import parquet.bytes.BytesInput;
import parquet.column.ColumnDescriptor;
import parquet.column.page.Page;
import parquet.column.page.PageReadStore;
import parquet.column.page.PageReader;
import parquet.hadoop.Footer;
import parquet.hadoop.ParquetFileReader;
import parquet.hadoop.ParquetFileWriter;
import parquet.hadoop.PrintFooter;
import parquet.hadoop.metadata.CompressionCodecName;
import parquet.hadoop.metadata.ParquetMetadata;
import parquet.schema.MessageType;
import parquet.schema.MessageTypeParser;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static parquet.column.Encoding.BIT_PACKED;
import static parquet.column.Encoding.PLAIN;

public class ParquetRecordReaderTest {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(StorageEngineRegistry.class);

  private String getResource(String resourceName) {
    return "resource:" + resourceName;
  }

  class MockOutputMutator implements OutputMutator {
    List<Integer> removedFields = Lists.newArrayList();
    List<ValueVector.Base> addFields = Lists.newArrayList();

    @Override
    public void removeField(int fieldId) throws SchemaChangeException {
      removedFields.add(fieldId);
    }

    @Override
    public void addField(int fieldId, ValueVector.Base vector) throws SchemaChangeException {
      addFields.add(vector);
    }

    @Override
    public void setNewSchema() throws SchemaChangeException {
    }

    List<Integer> getRemovedFields() {
      return removedFields;
    }

    List<ValueVector.Base> getAddFields() {
      return addFields;
    }
  }

  @Test
  public void testBasicWriteRead() throws Exception {

    File testFile = new File("exec/java-exec/src/test/resources/testParquetFile").getAbsoluteFile();
    System.out.println(testFile.toPath().toString());
    testFile.delete();

    Path path = new Path(testFile.toURI());
    Configuration configuration = new Configuration();

    //"message m { required int32 integer; required int64 integer64; required boolean b; required float f; required double d;}"

    // format: type, field name, uncompressed size, value1, value2, value3
    Object[][] fields = {
        {"int32", "integer", 4, -100, 100, Integer.MAX_VALUE},
        {"int64", "bigInt", 8, -5000l, 5000l, Long.MAX_VALUE},
        {"boolean", "b", 1, true, false, true},
        {"float", "f", 4, 1.74f, Float.MAX_VALUE, Float.MIN_VALUE},
        {"double", "d", 8, 1.74d, Double.MAX_VALUE, Double.MIN_VALUE}
    };
    String messageSchema = "message m {";
    for (Object[] fieldInfo : fields) {
      messageSchema += " required " + fieldInfo[0] + " " + fieldInfo[1] + ";";
    }
    // remove the last semicolon, java really needs a join method for strings...
    //messageSchema = messageSchema.substring(0, messageSchema.length() - 1);
    messageSchema += "}";

    MessageType schema = MessageTypeParser.parseMessageType(messageSchema);


    CompressionCodecName codec = CompressionCodecName.UNCOMPRESSED;
    ParquetFileWriter w = new ParquetFileWriter(configuration, schema, path);
    w.start();
    w.startBlock(1);
    for (Object[] fieldInfo : fields) {

      String[] path1 = {(String) fieldInfo[1]};
      ColumnDescriptor c1 = schema.getColumnDescription(path1);

      w.startColumn(c1, 5, codec);
      long c1Starts = w.getPos();
      byte[] bytes = new byte[(Integer) fieldInfo[2]];
      for (int i = 0; i < 1000; i++) {
        w.writeDataPage(2, 4, BytesInput.from(toByta(fieldInfo[3])), BIT_PACKED, BIT_PACKED, PLAIN);
        w.writeDataPage(2, 4, BytesInput.from(toByta(fieldInfo[4])), BIT_PACKED, BIT_PACKED, PLAIN);
        w.writeDataPage(2, 4, BytesInput.from(toByta(fieldInfo[5])), BIT_PACKED, BIT_PACKED, PLAIN);
      }
      w.endColumn();
      long c1Ends = w.getPos();
    }

    w.endBlock();
    w.end(new HashMap<String, String>());

    ParquetMetadata readFooter = ParquetFileReader.readFooter(configuration, path);

    PrintFooter.main(new String[]{path.toString()});
  }

  public static byte[] toByta(Object data) {
    if ( data instanceof Integer) return toByta((int) data);
    else if ( data instanceof Double) return toByta((double) data);
    else if ( data instanceof Float) return toByta((float) data);
    else if ( data instanceof Boolean) return toByta((boolean) data);
    else if ( data instanceof Long) return toByta((long) data);
    else return null;
  }
  // found at http://www.daniweb.com/software-development/java/code/216874/primitive-types-as-byte-arrays
  /* ========================= */
  /* "primitive type --> byte[] data" Methods */
  /* ========================= */
  public static byte[] toByta(byte data) {
    return new byte[]{data};
  }

  public static byte[] toByta(byte[] data) {
    return data;
  }

  /* ========================= */
  public static byte[] toByta(short data) {
    return new byte[]{
        (byte) ((data >> 8) & 0xff),
        (byte) ((data >> 0) & 0xff),
    };
  }

  public static byte[] toByta(short[] data) {
    if (data == null) return null;
    // ----------
    byte[] byts = new byte[data.length * 2];
    for (int i = 0; i < data.length; i++)
      System.arraycopy(toByta(data[i]), 0, byts, i * 2, 2);
    return byts;
  }

  /* ========================= */
  public static byte[] toByta(char data) {
    return new byte[]{
        (byte) ((data >> 8) & 0xff),
        (byte) ((data >> 0) & 0xff),
    };
  }

  public static byte[] toByta(char[] data) {
    if (data == null) return null;
    // ----------
    byte[] byts = new byte[data.length * 2];
    for (int i = 0; i < data.length; i++)
      System.arraycopy(toByta(data[i]), 0, byts, i * 2, 2);
    return byts;
  }

  /* ========================= */
  public static byte[] toByta(int data) {
    return new byte[]{
        (byte) ((data >> 24) & 0xff),
        (byte) ((data >> 16) & 0xff),
        (byte) ((data >> 8) & 0xff),
        (byte) ((data >> 0) & 0xff),
    };
  }

  public static byte[] toByta(int[] data) {
    if (data == null) return null;
    // ----------
    byte[] byts = new byte[data.length * 4];
    for (int i = 0; i < data.length; i++)
      System.arraycopy(toByta(data[i]), 0, byts, i * 4, 4);
    return byts;
  }

  /* ========================= */
  public static byte[] toByta(long data) {
    return new byte[]{
        (byte) ((data >> 56) & 0xff),
        (byte) ((data >> 48) & 0xff),
        (byte) ((data >> 40) & 0xff),
        (byte) ((data >> 32) & 0xff),
        (byte) ((data >> 24) & 0xff),
        (byte) ((data >> 16) & 0xff),
        (byte) ((data >> 8) & 0xff),
        (byte) ((data >> 0) & 0xff),
    };
  }

  public static byte[] toByta(long[] data) {
    if (data == null) return null;
    // ----------
    byte[] byts = new byte[data.length * 8];
    for (int i = 0; i < data.length; i++)
      System.arraycopy(toByta(data[i]), 0, byts, i * 8, 8);
    return byts;
  }

  /* ========================= */
  public static byte[] toByta(float data) {
    return toByta(Float.floatToRawIntBits(data));
  }

  public static byte[] toByta(float[] data) {
    if (data == null) return null;
    // ----------
    byte[] byts = new byte[data.length * 4];
    for (int i = 0; i < data.length; i++)
      System.arraycopy(toByta(data[i]), 0, byts, i * 4, 4);
    return byts;
  }

  /* ========================= */
  public static byte[] toByta(double data) {
    return toByta(Double.doubleToRawLongBits(data));
  }

  public static byte[] toByta(double[] data) {
    if (data == null) return null;
    // ----------
    byte[] byts = new byte[data.length * 8];
    for (int i = 0; i < data.length; i++)
      System.arraycopy(toByta(data[i]), 0, byts, i * 8, 8);
    return byts;
  }

  /* ========================= */
  public static byte[] toByta(boolean data) {
    return new byte[]{(byte) (data ? 0x01 : 0x00)}; // bool -> {1 byte}
  }

  public static byte[] toByta(boolean[] data) {
    // Advanced Technique: The byte array containts information
    // about how many boolean values are involved, so the exact
    // array is returned when later decoded.
    // ----------
    if (data == null) return null;
    // ----------
    int len = data.length;
    byte[] lena = toByta(len); // int conversion; length array = lena
    byte[] byts = new byte[lena.length + (len / 8) + (len % 8 != 0 ? 1 : 0)];
    // (Above) length-array-length + sets-of-8-booleans +? byte-for-remainder
    System.arraycopy(lena, 0, byts, 0, lena.length);
    // ----------
    // (Below) algorithm by Matthew Cudmore: boolean[] -> bits -> byte[]
    for (int i = 0, j = lena.length, k = 7; i < data.length; i++) {
      byts[j] |= (data[i] ? 1 : 0) << k--;
      if (k < 0) {
        j++;
        k = 7;
      }
    }
    // ----------
    return byts;
  }

  // above utility method found here: http://www.daniweb.com/software-development/java/code/216874/primitive-types-as-byte-arrays


  @Test
  public void parquetTest(@Injectable final FragmentContext context) throws IOException, ExecutionSetupException {
    new Expectations() {
      {
        context.getAllocator();
        returns(new DirectBufferAllocator());
      }
    };

    //
    MessageType schema = MessageTypeParser.parseMessageType("message m { required int64 b; }");
    String[] path1 = {"b"};
    ColumnDescriptor c1 = schema.getColumnDescription(path1);

    File testFile = FileUtils.getResourceAsFile("/testParquetFile").getAbsoluteFile();
    System.err.println(testFile.toPath().toString());
    //testFile.delete();

    Path path = new Path(testFile.toURI());
    Configuration configuration = new Configuration();
    ParquetMetadata readFooter = ParquetFileReader.readFooter(configuration, path);

    ParquetFileReader parReader = new ParquetFileReader(configuration, path, Arrays.asList(
        readFooter.getBlocks().get(0)), Arrays.asList(schema.getColumnDescription(path1)));
    ParquetRecordReader pr = new ParquetRecordReader(context, parReader, readFooter);

    MockOutputMutator mutator = new MockOutputMutator();
    List<ValueVector.Base> addFields = mutator.getAddFields();
    pr.setup(mutator);
    assertEquals(5, pr.next());
    assertEquals(1, addFields.size());
    assertEquals(0, pr.next());
  }

  private void validateFooters(final List<Footer> metadata) {
    logger.debug(metadata.toString());
    assertEquals(3, metadata.size());
    for (Footer footer : metadata) {
      final File file = new File(footer.getFile().toUri());
      assertTrue(file.getName(), file.getName().startsWith("part"));
      assertTrue(file.getPath(), file.exists());
      final ParquetMetadata parquetMetadata = footer.getParquetMetadata();
      assertEquals(2, parquetMetadata.getBlocks().size());
      final Map<String, String> keyValueMetaData = parquetMetadata.getFileMetaData().getKeyValueMetaData();
      assertEquals("bar", keyValueMetaData.get("foo"));
      assertEquals(footer.getFile().getName(), keyValueMetaData.get(footer.getFile().getName()));
    }
  }

  private void validateContains(MessageType schema, PageReadStore pages, String[] path, int values, BytesInput bytes) throws IOException {
    PageReader pageReader = pages.getPageReader(schema.getColumnDescription(path));
    Page page = pageReader.readPage();
    assertEquals(values, page.getValueCount());
    assertArrayEquals(bytes.toByteArray(), page.getBytes().toByteArray());
  }

}