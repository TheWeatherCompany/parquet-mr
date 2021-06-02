/**
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

package org.apache.parquet.avro;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Conversion;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.util.Utf8;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is based on org.apache.avro.TestCircularReferences
 *
 * The main difference between this class and the Avro version is that this one
 * uses a place-holder schema for the circular reference from Child to Parent.
 * This avoids creating a schema for Parent that references itself and can't be
 * converted to a Parquet schema. The place-holder schema must also have a
 * referenceable logical type.
 */
public class TestCircularReferences {

  private static final Logger LOG = LoggerFactory.getLogger(TestCircularReferences.class);

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  public static class Reference extends LogicalType {
    private static final String REFERENCE = "reference";
    private static final String REF_FIELD_NAME = "ref-field-name";

    private final String refFieldName;

    public Reference(String refFieldName) {
      super(REFERENCE);
      this.refFieldName = refFieldName;
    }

    public Reference(Schema schema) {
      super(REFERENCE);
      this.refFieldName = schema.getProp(REF_FIELD_NAME);
    }

    @Override
    public Schema addToSchema(Schema schema) {
      super.addToSchema(schema);
      schema.addProp(REF_FIELD_NAME, refFieldName);
      return schema;
    }

    @Override
    public String getName() {
      return REFERENCE;
    }

    public String getRefFieldName() {
      return refFieldName;
    }

    @Override
    public void validate(Schema schema) {
      super.validate(schema);
      if (schema.getField(refFieldName) == null) {
        throw new IllegalArgumentException("Invalid field name for reference field: " + refFieldName);
      }
    }
  }

  public static class Referenceable extends LogicalType {
    private static final String REFERENCEABLE = "referenceable";
    private static final String ID_FIELD_NAME = "id-field-name";

    private final String idFieldName;

    public Referenceable(String idFieldName) {
      super(REFERENCEABLE);
      this.idFieldName = idFieldName;
    }

    public Referenceable(Schema schema) {
      super(REFERENCEABLE);
      this.idFieldName = schema.getProp(ID_FIELD_NAME);
    }

    @Override
    public Schema addToSchema(Schema schema) {
      super.addToSchema(schema);
      schema.addProp(ID_FIELD_NAME, idFieldName);
      return schema;
    }

    @Override
    public String getName() {
      return REFERENCEABLE;
    }

    public String getIdFieldName() {
      return idFieldName;
    }

    @Override
    public void validate(Schema schema) {
      super.validate(schema);
      Schema.Field idField = schema.getField(idFieldName);
      if (idField == null || idField.schema().getType() != Schema.Type.LONG) {
        throw new IllegalArgumentException("Invalid ID field: " + idFieldName + ": " + idField);
      }
    }
  }

  @BeforeClass
  public static void addReferenceTypes() {
    LogicalTypes.register(Referenceable.REFERENCEABLE, new LogicalTypes.LogicalTypeFactory() {
      @Override
      public LogicalType fromSchema(Schema schema) {
        return new Referenceable(schema);
      }
    });
    LogicalTypes.register(Reference.REFERENCE, new LogicalTypes.LogicalTypeFactory() {
      @Override
      public LogicalType fromSchema(Schema schema) {
        return new Reference(schema);
      }
    });
  }

  public static class ReferenceManager {
    private interface Callback {
      void set(Object referenceable);
    }

    private final Map<Long, Object> references = new HashMap<Long, Object>();
    private final Map<Object, Long> ids = new IdentityHashMap<Object, Long>();
    private final Map<Long, List<Callback>> callbacksById = new HashMap<Long, List<Callback>>();
    private final ReferenceableTracker tracker = new ReferenceableTracker();
    private final ReferenceHandler handler = new ReferenceHandler();

    public ReferenceableTracker getTracker() {
      return tracker;
    }

    public ReferenceHandler getHandler() {
      return handler;
    }

    public class ReferenceableTracker extends Conversion<IndexedRecord> {
      @Override
      @SuppressWarnings("unchecked")
      public Class<IndexedRecord> getConvertedType() {
        return (Class) Record.class;
      }

      @Override
      public String getLogicalTypeName() {
        return Referenceable.REFERENCEABLE;
      }

      @Override
      public IndexedRecord fromRecord(IndexedRecord value, Schema schema, LogicalType type) {
        // read side
        long id = getId(value, schema);

        // keep track of this for later references
        references.put(id, value);

        // call any callbacks waiting to resolve this id
        List<Callback> callbacks = callbacksById.get(id);
        for (Callback callback : callbacks) {
          callback.set(value);
        }

        return value;
      }

      @Override
      public IndexedRecord toRecord(IndexedRecord value, Schema schema, LogicalType type) {
        // write side
        long id = getId(value, schema);

        // keep track of this for later references
        //references.put(id, value);
        ids.put(value, id);

        return value;
      }

      private long getId(IndexedRecord referenceable, Schema schema) {
        Referenceable info = (Referenceable) schema.getLogicalType();
        int idField = schema.getField(info.getIdFieldName()).pos();
        return (Long) referenceable.get(idField);
      }
    }

    public class ReferenceHandler extends Conversion<IndexedRecord> {
      @Override
      @SuppressWarnings("unchecked")
      public Class<IndexedRecord> getConvertedType() {
        return (Class) Record.class;
      }

      @Override
      public String getLogicalTypeName() {
        return Reference.REFERENCE;
      }

      @Override
      public IndexedRecord fromRecord(final IndexedRecord record, Schema schema, LogicalType type) {
        // read side: resolve the record or save a callback
        final Schema.Field refField = schema.getField(((Reference) type).getRefFieldName());

        Long id = (Long) record.get(refField.pos());
        if (id != null) {
          if (references.containsKey(id)) {
            record.put(refField.pos(), references.get(id));

          } else {
            List<Callback> callbacks = callbacksById.get(id);
            if (callbacks == null) {
              callbacks = new ArrayList<Callback>();
              callbacksById.put(id, callbacks);
            }
            // add a callback to resolve this reference when the id is available
            callbacks.add(new Callback() {
              @Override
              public void set(Object referenceable) {
                record.put(refField.pos(), referenceable);
              }
            });
          }
        }

        return record;
      }

      @Override
      public IndexedRecord toRecord(IndexedRecord record, Schema schema, LogicalType type) {
        // write side: replace a referenced field with its id
        Schema.Field refField = schema.getField(((Reference) type).getRefFieldName());
        IndexedRecord referenced = (IndexedRecord) record.get(refField.pos());
        if (referenced == null) {
          return record;
        }

        // hijack the field to return the id instead of the ref
        return new HijackingIndexedRecord(record, refField.pos(), ids.get(referenced));
      }
    }

    private static class HijackingIndexedRecord implements IndexedRecord {
      private final IndexedRecord wrapped;
      private final int index;
      private final Object data;

      public HijackingIndexedRecord(IndexedRecord wrapped, int index, Object data) {
        this.wrapped = wrapped;
        this.index = index;
        this.data = data;
      }

      @Override
      public void put(int i, Object v) {
        throw new RuntimeException("[BUG] This is a read-only class.");
      }

      @Override
      public Object get(int i) {
        if (i == index) {
          return data;
        }
        return wrapped.get(i);
      }

      @Override
      public Schema getSchema() {
        return wrapped.getSchema();
      }
    }
  }

  @Test
  public void test() throws IOException {
    ReferenceManager manager = new ReferenceManager();
    GenericData model = new GenericData();
    model.addLogicalTypeConversion(manager.getTracker());
    model.addLogicalTypeConversion(manager.getHandler());

    Schema parentSchema = Schema.createRecord("Parent", null, null, false);

    Schema placeholderSchema = Schema.createRecord("Placeholder", null, null, false);
    List<Schema.Field> placeholderFields = new ArrayList<Schema.Field>();
    placeholderFields.add( // at least one field is needed to be a valid schema
        new Schema.Field("id", Schema.create(Schema.Type.LONG), null, null));
    placeholderSchema.setFields(placeholderFields);

    Referenceable idRef = new Referenceable("id");

    Schema parentRefSchema = Schema.createUnion(
        Schema.create(Schema.Type.NULL),
        Schema.create(Schema.Type.LONG),
        idRef.addToSchema(placeholderSchema));

    Reference parentRef = new Reference("parent");

    List<Schema.Field> childFields = new ArrayList<Schema.Field>();
    childFields.add(new Schema.Field("c", Schema.create(Schema.Type.STRING), null, null));
    childFields.add(new Schema.Field("parent", parentRefSchema, null, null));
    Schema childSchema = parentRef.addToSchema(
        Schema.createRecord("Child", null, null, false, childFields));

    List<Schema.Field> parentFields = new ArrayList<Schema.Field>();
    parentFields.add(new Schema.Field("id", Schema.create(Schema.Type.LONG), null, null));
    parentFields.add(new Schema.Field("p", Schema.create(Schema.Type.STRING), null, null));
    parentFields.add(new Schema.Field("child", childSchema, null, null));
    parentSchema.setFields(parentFields);

    Schema schema = idRef.addToSchema(parentSchema);

    LOG.debug("Schema: {}", schema.toString(true));

    Record parent = new Record(schema);
    parent.put("id", 1L);
    parent.put("p", "parent data!");

    Record child = new Record(childSchema);
    child.put("c", "child data!");
    child.put("parent", parent);

    parent.put("child", child);

    // serialization round trip
    File data = AvroTestUtil.write(temp, model, schema, parent);
    List<Record> records = AvroTestUtil.read(model, schema, data);

    Record actual = records.get(0);

    // because the record is a recursive structure, equals won't work
    Assert.assertEquals("Should correctly read back the parent id",
        1L, actual.get("id"));
    Assert.assertEquals("Should correctly read back the parent data",
        new Utf8("parent data!"), actual.get("p"));

    Record actualChild = (Record) actual.get("child");
    Assert.assertEquals("Should correctly read back the child data",
        new Utf8("child data!"), actualChild.get("c"));
    Object childParent = actualChild.get("parent");
    Assert.assertTrue("Should have a parent Record object",
        childParent instanceof Record);

    Record childParentRecord = (Record) actualChild.get("parent");
    Assert.assertEquals("Should have the right parent id",
        1L, childParentRecord.get("id"));
    Assert.assertEquals("Should have the right parent data",
        new Utf8("parent data!"), childParentRecord.get("p"));
  }
}
