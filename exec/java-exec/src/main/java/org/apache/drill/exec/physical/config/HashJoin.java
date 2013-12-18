/**
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
 */
package org.apache.drill.exec.physical.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import org.apache.drill.common.logical.data.Join;
import org.apache.drill.common.logical.data.JoinCondition;
import org.apache.drill.exec.physical.OperatorCost;
import org.apache.drill.exec.physical.base.AbstractBase;
import org.apache.drill.exec.physical.base.PhysicalOperator;
import org.apache.drill.exec.physical.base.PhysicalVisitor;
import org.apache.drill.exec.physical.base.Size;

import java.util.Iterator;
import java.util.List;

@JsonTypeName("hash-join")
public class HashJoin extends AbstractBase {
  static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MergeJoinPOP.class);


  private final PhysicalOperator left;
  private final PhysicalOperator right;
  private final List<JoinCondition> conditions;
  private final Join.JoinType joinType;

  @Override
  public OperatorCost getCost() {
    return new OperatorCost(0, 0, 0, 0);
  }

  @JsonCreator
  public HashJoin(
      @JsonProperty("left") PhysicalOperator left,
      @JsonProperty("right") PhysicalOperator right,
      @JsonProperty("join-conditions") List<JoinCondition> conditions,
      @JsonProperty("join-type") Join.JoinType joinType
  ) {
    this.left = left;
    this.right = right;
    this.conditions = conditions;
    this.joinType = joinType;
  }

  @Override
  public Size getSize() {
    return left.getSize().add(right.getSize());
  }

  @Override
  public <T, X, E extends Throwable> T accept(PhysicalVisitor<T, X, E> physicalVisitor, X value) throws E {
    return physicalVisitor.visitHashJoin(this, value);
  }

  @Override
  public PhysicalOperator getNewWithChildren(List<PhysicalOperator> children) {
    Preconditions.checkArgument(children.size() == 2);
    return new HashJoin(children.get(0), children.get(1), conditions, joinType);
  }

  @Override
  public Iterator<PhysicalOperator> iterator() {
    return Iterators.forArray(left, right);
  }

  public PhysicalOperator getLeft() {
    return left;
  }

  public PhysicalOperator getRight() {
    return right;
  }

  public Join.JoinType getJoinType() {
    return joinType;
  }

  public List<JoinCondition> getConditions() {
    return conditions;
  }

  public HashJoin flipIfRight() {
    if (joinType == Join.JoinType.RIGHT) {
      List<JoinCondition> flippedConditions = Lists.newArrayList(conditions);
      for (JoinCondition c : conditions) {
        flippedConditions.add(c.flip());
      }
      return new HashJoin(right, left, flippedConditions, Join.JoinType.LEFT);
    } else {
      return this;
    }

  }
}
