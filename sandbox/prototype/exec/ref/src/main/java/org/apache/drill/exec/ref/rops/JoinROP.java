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
package org.apache.drill.exec.ref.rops;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.drill.common.exceptions.DrillRuntimeException;
import org.apache.drill.common.logical.data.Join;
import org.apache.drill.common.logical.data.JoinType;
import org.apache.drill.exec.ref.IteratorRegistry;
import org.apache.drill.exec.ref.RecordIterator;
import org.apache.drill.exec.ref.RecordPointer;
import org.apache.drill.exec.ref.eval.EvaluatorFactory;
import org.apache.drill.exec.ref.eval.fn.ComparisonEvaluators;
import org.apache.drill.exec.ref.exceptions.RecordException;
import org.apache.drill.exec.ref.exceptions.SetupException;
import org.apache.drill.exec.ref.values.ComparableValue;
import org.apache.drill.exec.ref.values.DataValue;

import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.drill.common.logical.data.JoinType.*;
import static org.apache.drill.common.logical.data.JoinType.right;

public class JoinROP extends ROPBase<Join> {
    static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(JoinROP.class);

    private RecordIterator left;
    private RecordIterator right;
    private ProxyJoinedRecord record;
    private EvaluatorFactory factory;

    public JoinROP(Join config) {
        super(config);
        record = new ProxyJoinedRecord();
    }

    @Override
    protected void setupIterators(IteratorRegistry builder) {
        left = Iterables.getOnlyElement(builder.getOperator(config.getLeft()));
        right = Iterables.getOnlyElement(builder.getOperator(config.getRight()));
    }

    @Override
    protected void setupEvals(EvaluatorFactory builder) throws SetupException {
        factory = builder;
    }

    @Override
    protected RecordIterator getIteratorInternal() {
        return createIteratorFromJoin(config.getType());
    }

    private RecordIterator createIteratorFromJoin(JoinType type) {
        switch (type) {
            case left:
                return new LeftIterator();
            case inner:
                return new InnerIterator();
            case outer:
                return new OuterIterator();
            case right:
                return new RightIterator();
            default:
                throw new UnsupportedOperationException("Type not supported: " + type);
        }
    }

    private class RecordBuffer {
        final boolean schemaChanged;
        final RecordPointer pointer;

        private RecordBuffer(RecordPointer pointer, boolean schemaChanged) {
            this.pointer = pointer;
            this.schemaChanged = schemaChanged;
        }
    }

    abstract class JoinIterator implements RecordIterator {
        protected List<RecordBuffer> buffer;
        protected int curIdx = 0;
        protected int bufferLength = 0;

        protected abstract int setupBuffer();

        @Override
        public RecordPointer getRecordPointer() {
            return record;
        }

        public NextOutcome next() {
            if (buffer == null) {
                buffer = Lists.newArrayList();
                setupBuffer();
                bufferLength = buffer.size();
            }
            return getNext();
        }

        public abstract NextOutcome getNext();

        protected void setJoinedRecord(RecordPointer left, RecordPointer right) {
            record.setRecord(left, right);
        }

        public boolean eval(DataValue leftVal, DataValue rightVal, String relationship) {
            //Somehow utilize ComparisonEvaluators?
            switch (relationship.toLowerCase()) {
                case "equals":
                    return leftVal.equals(rightVal);
                case "less than":
                    checkComparable(leftVal, rightVal);
                    return ((ComparableValue) leftVal).compareTo(rightVal) < 0;
                case "greater than":
                    checkComparable(leftVal, rightVal);
                    return ((ComparableValue) leftVal).compareTo(rightVal) > 0;
                default:
                    throw new DrillRuntimeException("Relationship not yet supported: " + relationship);
            }
        }

        private void checkComparable(DataValue a, DataValue b) {
            if (!ComparisonEvaluators.isComparable(a, b)) {
                throw new RecordException(String.format("Values cannot be compared.  A %s cannot be compared to a %s.", a, b), null);
            }
        }

        @Override
        public ROP getParent() {
            return JoinROP.this;
        }
    }

    class InnerIterator extends JoinIterator {
        @Override
        protected int setupBuffer() {
            return 0;
        }

        @Override
        public NextOutcome getNext() {
            return null;
        }
    }

    class LeftIterator extends JoinIterator {
        private NextOutcome leftOutcome;

        @Override
        protected int setupBuffer() {
            int count = 0;
            NextOutcome outcome = right.next();
            while (outcome != NextOutcome.NONE_LEFT) {
                buffer.add(new RecordBuffer(
                        right.getRecordPointer().copy(),
                        outcome == NextOutcome.INCREMENTED_SCHEMA_CHANGED)
                );
                ++count;
                outcome = right.next();
            }
            return count;
        }

        @Override
        public NextOutcome getNext() {
            final RecordPointer leftPointer = left.getRecordPointer();
            boolean isFound = true;
            while (true) {
                if (curIdx == 0) {
                    if (!isFound) {
                        record.setRecord(leftPointer, null);
                        return leftOutcome;
                    }

                    leftOutcome = left.next();

                    if (leftOutcome == NextOutcome.NONE_LEFT) {
                        break;
                    }

                    isFound = false;
                }

                final RecordBuffer bufferObj = buffer.get(curIdx++);
                Optional<Join.JoinCondition> option = Iterables.tryFind(Lists.newArrayList(config.getConditions()), new Predicate<Join.JoinCondition>() {
                    @Override
                    public boolean apply(Join.JoinCondition condition) {
                        return eval(factory.getBasicEvaluator(leftPointer, condition.getLeft()).eval(),
                                factory.getBasicEvaluator(bufferObj.pointer, condition.getRight()).eval(), condition.getRelationship());
                    }
                });

                if (option.isPresent()) {
                    setJoinedRecord(leftPointer, bufferObj.pointer);
                    return (bufferObj.schemaChanged || leftOutcome == NextOutcome.INCREMENTED_SCHEMA_CHANGED) ?
                            NextOutcome.INCREMENTED_SCHEMA_CHANGED :
                            NextOutcome.INCREMENTED_SCHEMA_UNCHANGED;
                }

                if (curIdx >= bufferLength) {
                    curIdx = 0;
                }
            }

            return NextOutcome.NONE_LEFT;
        }
    }

    class RightIterator extends JoinIterator {
        NextOutcome rightOutcome;

        @Override
        protected int setupBuffer() {
            int count = 0;
            NextOutcome outcome = left.next();
            while (outcome != NextOutcome.NONE_LEFT) {
                buffer.add(new RecordBuffer(
                        left.getRecordPointer().copy(),
                        outcome == NextOutcome.INCREMENTED_SCHEMA_CHANGED)
                );
                ++count;
                outcome = left.next();
            }
            return count;
        }

        @Override
        public NextOutcome getNext() {
            final RecordPointer rightPointer = right.getRecordPointer();
            boolean isFound = true;
            while (true) {
                if (curIdx == 0) {
                    if (!isFound) {
                        record.setRecord(null, rightPointer);
                        return rightOutcome;
                    }

                    rightOutcome = right.next();

                    if (rightOutcome == NextOutcome.NONE_LEFT) {
                        break;
                    }

                    isFound = false;
                }

                final RecordBuffer bufferObj = buffer.get(curIdx++);
                Optional<Join.JoinCondition> option = Iterables.tryFind(Lists.newArrayList(config.getConditions()), new Predicate<Join.JoinCondition>() {
                    @Override
                    public boolean apply(Join.JoinCondition condition) {
                        return eval(factory.getBasicEvaluator(rightPointer, condition.getRight()).eval(),
                                factory.getBasicEvaluator(bufferObj.pointer, condition.getLeft()).eval(), condition.getRelationship());
                    }
                });

                if (option.isPresent()) {
                    setJoinedRecord(rightPointer, bufferObj.pointer);
                    return (bufferObj.schemaChanged || rightOutcome == NextOutcome.INCREMENTED_SCHEMA_CHANGED) ?
                            NextOutcome.INCREMENTED_SCHEMA_CHANGED :
                            NextOutcome.INCREMENTED_SCHEMA_UNCHANGED;
                }

                if (curIdx >= bufferLength) {
                    curIdx = 0;
                }
            }

            return NextOutcome.NONE_LEFT;
        }
    }

    class OuterIterator extends JoinIterator {

        @Override
        protected int setupBuffer() {
            return 0;
        }

        @Override
        public NextOutcome getNext() {
            return null;
        }
    }
}
