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

package org.apache.flink.table.types;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.table.api.DataTypes;
import org.apache.flink.table.api.ValidationException;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.types.logical.LogicalType;
import org.apache.flink.table.types.logical.LogicalTypeRoot;
import org.apache.flink.table.types.logical.utils.LogicalTypeChecks;
import org.apache.flink.table.types.utils.DataTypeUtils;
import org.apache.flink.util.Preconditions;

import javax.annotation.Nullable;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.apache.flink.table.types.logical.utils.LogicalTypeChecks.isCompositeType;

/**
 * Describes the data type of a value in the table ecosystem. Instances of this class can be used to
 * declare input and/or output types of operations.
 *
 * <p>The {@link DataType} class has two responsibilities: declaring a logical type and giving hints
 * about the physical representation of data to the planner. While the logical type is mandatory,
 * hints are optional but useful at the edges to other APIs.
 *
 * <p>The logical type is independent of any physical representation and is close to the "data type"
 * terminology of the SQL standard. See {@link org.apache.flink.table.types.logical.LogicalType} and
 * its subclasses for more information about available logical types and their properties.
 *
 * <p>Physical hints are required at the edges of the table ecosystem. Hints indicate the data
 * format that an implementation expects. For example, a data source could express that it produces
 * values for logical timestamps using a {@link java.sql.Timestamp} class instead of using {@link
 * java.time.LocalDateTime}. With this information, the runtime is able to convert the produced
 * class into its internal data format. In return, a data sink can declare the data format it
 * consumes from the runtime.
 *
 * @see DataTypes for a list of supported data types and instances of this class.
 */
@PublicEvolving
public abstract class DataType implements AbstractDataType<DataType>, Serializable {

    protected final LogicalType logicalType;

    protected final Class<?> conversionClass;

    DataType(LogicalType logicalType, @Nullable Class<?> conversionClass) {
        this.logicalType =
                Preconditions.checkNotNull(logicalType, "Logical type must not be null.");
        this.conversionClass =
                performEarlyClassValidation(
                        logicalType, ensureConversionClass(logicalType, conversionClass));
    }

    /**
     * Returns the corresponding logical type.
     *
     * @return a parameterized instance of {@link LogicalType}
     */
    public LogicalType getLogicalType() {
        return logicalType;
    }

    /**
     * Returns the corresponding conversion class for representing values. If no conversion class
     * was defined manually, the default conversion defined by the logical type is used.
     *
     * @see LogicalType#getDefaultConversion()
     * @return the expected conversion class
     */
    public Class<?> getConversionClass() {
        return conversionClass;
    }

    /**
     * Returns the children of this data type, if any. Returns an empty list if this data type is
     * atomic.
     *
     * @return the children data types
     */
    public abstract List<DataType> getChildren();

    public abstract <R> R accept(DataTypeVisitor<R> visitor);

    /**
     * Creates a copy of this {@link DataType} instance with the internal data type conversion
     * classes. This method performs the transformation deeply through its children. For example,
     * for a {@link DataType} instance representing a row type with a timestamp field, this method
     * returns a new {@link DataType}, with the conversion class to {@link RowData} and the children
     * data type with the conversion class to {@link TimestampData}.
     *
     * <p>For a comprehensive list of internal data types, check {@link RowData}.
     *
     * @see RowData
     */
    public DataType toInternal() {
        return DataTypeUtils.toInternalDataType(this);
    }

    @Override
    public String toString() {
        return logicalType.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        DataType dataType = (DataType) o;
        return logicalType.equals(dataType.logicalType)
                && conversionClass.equals(dataType.conversionClass);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logicalType, conversionClass);
    }

    // --------------------------------------------------------------------------------------------
    // Utilities for Common Data Type Transformations
    // --------------------------------------------------------------------------------------------

    /**
     * Returns the first-level field names for the provided {@link DataType}.
     *
     * <p>Note: This method returns an empty list for every {@link DataType} that is not a composite
     * type.
     */
    public static List<String> getFieldNames(DataType dataType) {
        final LogicalType type = dataType.getLogicalType();
        if (type.is(LogicalTypeRoot.DISTINCT_TYPE)) {
            return getFieldNames(dataType.getChildren().get(0));
        } else if (isCompositeType(type)) {
            return LogicalTypeChecks.getFieldNames(type);
        }
        return Collections.emptyList();
    }

    /**
     * Returns the first-level field data types for the provided {@link DataType}.
     *
     * <p>Note: This method returns an empty list for every {@link DataType} that is not a composite
     * type.
     */
    public static List<DataType> getFieldDataTypes(DataType dataType) {
        final LogicalType type = dataType.getLogicalType();
        if (type.is(LogicalTypeRoot.DISTINCT_TYPE)) {
            return getFieldDataTypes(dataType.getChildren().get(0));
        } else if (isCompositeType(type)) {
            return dataType.getChildren();
        }
        return Collections.emptyList();
    }

    /**
     * Returns the count of the first-level fields for the provided {@link DataType}.
     *
     * <p>Note: This method returns {@code 0} for every {@link DataType} that is not a composite
     * type.
     */
    public static int getFieldCount(DataType dataType) {
        return getFieldDataTypes(dataType).size();
    }

    /**
     * Projects a (possibly nested) row data type by returning a new data type that only includes
     * fields of the given index paths.
     *
     * <p>Note: Index paths allow for arbitrary deep nesting. For example, {@code [[0, 2, 1], ...]}
     * specifies to include the 2nd field of the 3rd field of the 1st field in the top-level row.
     * Sometimes, name conflicts might occur when extracting fields from a row. Considering the path
     * is unique to extract fields, it makes sense to use the path to the fields with delimiter `_`
     * as the new name of the field. For example, the new name of the field `b` in the row `a` is
     * `a_b` rather than `b`. However, name conflicts are still possible in some cases, e.g. if the
     * field name is`a_b` in the top level row. In this case, the method will use a postfix in the
     * format '_$%d' to resolve the name conflicts.
     */
    public static DataType projectFields(DataType dataType, int[][] indexPaths) {
        return DataTypeUtils.projectRow(dataType, indexPaths);
    }

    /**
     * Projects a (possibly nested) row data type by returning a new data type that only includes
     * fields of the given indices.
     *
     * <p>Note: This method only projects (possibly nested) fields in the top-level row.
     */
    public static DataType projectFields(DataType dataType, int[] indexes) {
        return DataTypeUtils.projectRow(dataType, indexes);
    }

    /**
     * Exclude fields with the provided {@code indexes} from the {@code dataType}. This method
     * behaves as the inverse method of {@link #projectFields(DataType, int[])}.
     *
     * <p>Note: This method only excludes (possibly nested) fields in the top-level row.
     */
    public static DataType excludeFields(DataType dataType, int[] indexes) {
        // Convert indexes to set
        final Set<Integer> indexesSet = new HashSet<>();
        for (int index : indexes) {
            indexesSet.add(index);
        }

        // Compute projection
        final int[] projection =
                IntStream.range(0, DataType.getFieldCount(dataType))
                        .filter(i -> !indexesSet.contains(i))
                        .toArray();

        return DataType.projectFields(dataType, projection);
    }

    /**
     * Returns an ordered list of fields starting from the provided {@link DataType}.
     *
     * <p>Note: This method returns an empty list for every {@link DataType} that is not a composite
     * type.
     */
    public static List<DataTypes.Field> getFields(DataType dataType) {
        final List<String> names = getFieldNames(dataType);
        final List<DataType> dataTypes = getFieldDataTypes(dataType);
        return IntStream.range(0, names.size())
                .mapToObj(i -> DataTypes.FIELD(names.get(i), dataTypes.get(i)))
                .collect(Collectors.toList());
    }

    // --------------------------------------------------------------------------------------------

    /**
     * This method should catch the most common errors. However, another validation is required in
     * deeper layers as we don't know whether the data type is used for input or output declaration.
     */
    private static <C> Class<C> performEarlyClassValidation(
            LogicalType logicalType, Class<C> candidate) {

        if (candidate != null
                && !logicalType.supportsInputConversion(candidate)
                && !logicalType.supportsOutputConversion(candidate)) {
            throw new ValidationException(
                    String.format(
                            "Logical type '%s' does not support a conversion from or to class '%s'.",
                            logicalType.asSummaryString(), candidate.getName()));
        }
        return candidate;
    }

    private static Class<?> ensureConversionClass(
            LogicalType logicalType, @Nullable Class<?> clazz) {
        if (clazz == null) {
            return logicalType.getDefaultConversion();
        }
        return clazz;
    }
}
