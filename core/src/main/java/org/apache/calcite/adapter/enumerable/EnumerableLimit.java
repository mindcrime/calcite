/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.adapter.enumerable;

import org.apache.calcite.linq4j.tree.BlockBuilder;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollationTraitDef;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributionTraitDef;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.SingleRel;
import org.apache.calcite.rel.metadata.RelMdCollation;
import org.apache.calcite.rel.metadata.RelMdDistribution;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.util.BuiltInMethod;

import com.google.common.base.Supplier;

import java.util.List;

/** Relational expression that applies a limit and/or offset to its input. */
public class EnumerableLimit extends SingleRel implements EnumerableRel {
  private final RexNode offset;
  private final RexNode fetch;

  /** Creates an EnumerableLimit.
   *
   * <p>Use {@link #create} unless you know what you're doing. */
  public EnumerableLimit(
      RelOptCluster cluster,
      RelTraitSet traitSet,
      RelNode input,
      RexNode offset,
      RexNode fetch) {
    super(cluster, traitSet, input);
    this.offset = offset;
    this.fetch = fetch;
    assert getConvention() instanceof EnumerableConvention;
    assert getConvention() == input.getConvention();
  }

  /** Creates an EnumerableLimit. */
  public static EnumerableLimit create(final RelNode input, RexNode offset,
      RexNode fetch) {
    final RelOptCluster cluster = input.getCluster();
    final RelTraitSet traitSet =
        cluster.traitSetOf(EnumerableConvention.INSTANCE)
            .replaceIfs(
                RelCollationTraitDef.INSTANCE,
                new Supplier<List<RelCollation>>() {
                  public List<RelCollation> get() {
                    return RelMdCollation.limit(input);
                  }
                })
            .replaceIf(RelDistributionTraitDef.INSTANCE,
                new Supplier<RelDistribution>() {
                  public RelDistribution get() {
                    return RelMdDistribution.limit(input);
                  }
                });
    return new EnumerableLimit(cluster, traitSet, input, offset, fetch);
  }

  @Override public EnumerableLimit copy(
      RelTraitSet traitSet,
      List<RelNode> newInputs) {
    return new EnumerableLimit(
        getCluster(),
        traitSet,
        sole(newInputs),
        offset,
        fetch);
  }

  @Override public RelWriter explainTerms(RelWriter pw) {
    return super.explainTerms(pw)
        .itemIf("offset", offset, offset != null)
        .itemIf("fetch", fetch, fetch != null);
  }

  @Override public double getRows() {
    double rowCount = super.getRows();
    final int offset =
        this.offset == null ? 0 : RexLiteral.intValue(this.offset);
    rowCount = Math.max(rowCount - offset, 0D);

    if (this.fetch != null) {
      final int limit = RexLiteral.intValue(this.fetch);
      if (limit < rowCount) {
        return (double) limit;
      }
    }
    return rowCount;
  }

  public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
    final BlockBuilder builder = new BlockBuilder();
    final EnumerableRel child = (EnumerableRel) getInput();
    final Result result = implementor.visitChild(this, 0, child, pref);
    final PhysType physType =
        PhysTypeImpl.of(
            implementor.getTypeFactory(),
            getRowType(),
            result.format);

    Expression v = builder.append("child", result.block);
    if (offset != null) {
      v = builder.append(
          "offset",
          Expressions.call(
              v,
              BuiltInMethod.SKIP.method,
              Expressions.constant(RexLiteral.intValue(offset))));
    }
    if (fetch != null) {
      v = builder.append(
          "fetch",
          Expressions.call(
              v,
              BuiltInMethod.TAKE.method,
              Expressions.constant(RexLiteral.intValue(fetch))));
    }

    builder.add(
        Expressions.return_(
            null,
            v));
    return implementor.result(physType, builder.toBlock());
  }
}

// End EnumerableLimit.java
