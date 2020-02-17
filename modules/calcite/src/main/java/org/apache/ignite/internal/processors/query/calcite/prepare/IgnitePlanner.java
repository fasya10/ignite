/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.processors.query.calcite.prepare;

import com.google.common.collect.ImmutableList;
import java.io.Reader;
import java.util.List;
import org.apache.calcite.adapter.java.JavaTypeFactory;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptLattice;
import org.apache.calcite.plan.RelOptMaterialization;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitDef;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.plan.volcano.VolcanoUtils;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.metadata.CachingRelMetadataProvider;
import org.apache.calcite.rel.metadata.JaninoRelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataProvider;
import org.apache.calcite.rel.metadata.RelMetadataQuery;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexExecutor;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlRexConvertletTable;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.Program;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.ValidationException;
import org.apache.calcite.util.Pair;
import org.apache.ignite.internal.processors.cache.query.IgniteQueryErrorCode;
import org.apache.ignite.internal.processors.query.IgniteSQLException;
import org.apache.ignite.internal.processors.query.calcite.metadata.IgniteMetadata;
import org.apache.ignite.internal.processors.query.calcite.rel.IgniteConvention;
import org.apache.ignite.internal.processors.query.calcite.rel.IgniteRel;
import org.apache.ignite.internal.processors.query.calcite.serialize.relation.GraphToRelConverter;
import org.apache.ignite.internal.processors.query.calcite.serialize.relation.RelGraph;
import org.apache.ignite.internal.processors.query.calcite.serialize.relation.RelToGraphConverter;

/**
 * Query planer.
 */
public class IgnitePlanner implements Planner, RelOptTable.ViewExpander {
    /** */
    private final SqlOperatorTable operatorTable;

    /** */
    private final ImmutableList<Program> programs;

    /** */
    private final FrameworkConfig frameworkConfig;

    /** */
    private final PlanningContext ctx;

    /** */
    private final CalciteConnectionConfig connectionConfig;

    /** */
    @SuppressWarnings("rawtypes")
    private final ImmutableList<RelTraitDef> traitDefs;

    /** */
    private final SqlParser.Config parserConfig;

    /** */
    private final SqlToRelConverter.Config sqlToRelConverterConfig;

    /** */
    private final SqlRexConvertletTable convertletTable;

    /** */
    private final RexExecutor rexExecutor;

    /** */
    private final SchemaPlus defaultSchema;

    /** */
    private final JavaTypeFactory typeFactory;

    /** */
    private RelOptPlanner planner;

    /** */
    private SqlValidator validator;

    /**
     * @param ctx Planner context.
     */
    IgnitePlanner(PlanningContext ctx) {
        this.ctx = ctx;

        frameworkConfig = ctx.frameworkConfig();
        connectionConfig = ctx.connectionConfig();
        typeFactory = ctx.typeFactory();

        defaultSchema = frameworkConfig.getDefaultSchema();
        operatorTable = frameworkConfig.getOperatorTable();
        programs = frameworkConfig.getPrograms();
        parserConfig = frameworkConfig.getParserConfig();
        sqlToRelConverterConfig = frameworkConfig.getSqlToRelConverterConfig();
        convertletTable = frameworkConfig.getConvertletTable();
        rexExecutor = frameworkConfig.getExecutor();
        traitDefs = frameworkConfig.getTraitDefs();
    }

    /** {@inheritDoc} */
    @Override public RelTraitSet getEmptyTraitSet() {
        return planner().emptyTraitSet();
    }

    /** {@inheritDoc} */
    @Override public void close() {
        reset();
    }

    /** {@inheritDoc} */
    @Override public void reset() {
        planner = null;
        validator = null;
    }

    /**
     * @return Planner context.
     */
    public PlanningContext context() {
        return ctx;
    }

    /** {@inheritDoc} */
    @Override public SqlNode parse(Reader reader) throws SqlParseException {
        SqlNodeList sqlNodes = SqlParser.create(reader, parserConfig).parseStmtList();

        return sqlNodes.size() == 1 ? sqlNodes.get(0) : sqlNodes;
    }

    /** {@inheritDoc} */
    @Override public SqlNode validate(SqlNode sqlNode) throws ValidationException {
        try {
            return validator().validate(sqlNode);
        }
        catch (RuntimeException e) {
            throw new ValidationException(e);
        }
    }

    /** {@inheritDoc} */
    @Override public Pair<SqlNode, RelDataType> validateAndGetType(SqlNode sqlNode) throws ValidationException {
        SqlNode validatedNode = validator().validate(sqlNode);
        RelDataType type = validator().getValidatedNodeType(validatedNode);
        return Pair.of(validatedNode, type);
    }

    /**
     * Validates a SQL statement.
     *
     * @param sqlNode Root node of the SQL parse tree.
     * @return Validated node, its validated type and type's origins.
     * @throws ValidationException if not valid
     */
    public ValidationResult validateAndGetTypeMetadata(SqlNode sqlNode) throws ValidationException {
        SqlNode validatedNode = validator().validate(sqlNode);
        RelDataType type = validator().getValidatedNodeType(validatedNode);
        List<List<String>> origins = validator().getFieldOrigins(validatedNode);

        return new ValidationResult(validatedNode, type, origins);
    }

    /** {@inheritDoc} */
    @Override public RelNode convert(SqlNode sql) {
        return rel(sql).rel;
    }

    /**
     * Converts intermediate relational nodes tree representation into a relational nodes tree, bounded to the planner.
     *
     * @param graph Relational nodes tree representation.
     * @return Root node of relational tree.
     */
    public IgniteRel convert(RelGraph graph) {
        RelOptCluster cluster = createCluster();
        RelBuilder relBuilder = createRelBuilder(cluster, createCatalogReader());

        return new GraphToRelConverter(this, relBuilder, operatorTable).convert(graph);
    }

    /** Creates a cluster. */
    RelOptCluster createCluster() {
        RelOptCluster cluster = RelOptCluster.create(planner(), createRexBuilder());
        cluster.setMetadataProvider(IgniteMetadata.METADATA_PROVIDER);
        return cluster;
    }

    /** {@inheritDoc} */
    @Override public RelRoot rel(SqlNode sql) {
        RelOptCluster cluster = createCluster();
        SqlToRelConverter.Config config = SqlToRelConverter.configBuilder()
            .withConfig(sqlToRelConverterConfig)
            .withTrimUnusedFields(false)
            .withConvertTableAccess(false)
            .build();
        SqlToRelConverter sqlToRelConverter =
            new SqlToRelConverter(this, validator, createCatalogReader(), cluster, convertletTable, config);

        return sqlToRelConverter.convertQuery(sql, false, true);
    }

    /**
     * Creates an intermediate relational nodes tree representation for a given relational nodes tree.
     *
     * @param rel Root node of relational tree.
     * @return Relational nodes tree representation.
     */
    public RelGraph graph(RelNode rel) {
        if (rel.getConvention() != IgniteConvention.INSTANCE)
            throw new IllegalArgumentException("Physical node is required.");

        return new RelToGraphConverter().go((IgniteRel) rel);
    }

    /** {@inheritDoc} */
    @Override public RelRoot expandView(RelDataType rowType, String queryString, List<String> schemaPath, List<String> viewPath) {
        SqlParser parser = SqlParser.create(queryString, parserConfig);
        SqlNode sqlNode;
        try {
            sqlNode = parser.parseQuery();
        }
        catch (SqlParseException e) {
            throw new IgniteSQLException("parse failed", IgniteQueryErrorCode.PARSING, e);
        }

        SqlConformance conformance = conformance();
        CalciteCatalogReader catalogReader =
            createCatalogReader().withSchemaPath(schemaPath);
        SqlValidator validator = new IgniteSqlValidator(operatorTable(), catalogReader, typeFactory, conformance);
        validator.setIdentifierExpansion(true);

        RelOptCluster cluster = createCluster();
        SqlToRelConverter.Config config = SqlToRelConverter
            .configBuilder()
            .withConfig(sqlToRelConverterConfig)
            .withTrimUnusedFields(false)
            .withConvertTableAccess(false)
            .build();
        SqlToRelConverter sqlToRelConverter =
            new SqlToRelConverter(this, validator, catalogReader, cluster, convertletTable, config);

        return sqlToRelConverter.convertQuery(sqlNode, true, false);
    }

    /** {@inheritDoc} */
    @Override public RelNode transform(int programIdx, RelTraitSet targetTraits, RelNode rel) {
        RelMetadataProvider clusterProvider = rel.getCluster().getMetadataProvider();
        JaninoRelMetadataProvider threadProvider = RelMetadataQuery.THREAD_PROVIDERS.get();
        try {
            rel.getCluster().setMetadataProvider(new CachingRelMetadataProvider(IgniteMetadata.METADATA_PROVIDER, planner()));
            RelMetadataQuery.THREAD_PROVIDERS.set(JaninoRelMetadataProvider.of(rel.getCluster().getMetadataProvider()));

            return programs.get(programIdx).run(planner(), rel, targetTraits.simplify(), materializations(), latices());
        }
        finally {
            rel.getCluster().setMetadataProvider(clusterProvider);
            RelMetadataQuery.THREAD_PROVIDERS.set(threadProvider);
        }
    }

    /**
     * Converts one relational nodes tree into another relational nodes tree
     * based on a particular planner type, planning phase and required set of traits.
     * @param phase Planner phase.
     * @param targetTraits Target traits.
     * @param rel Root node of relational tree.
     * @return The root of the new RelNode tree.
     */
    public <T extends RelNode> T transform(PlannerPhase phase, RelTraitSet targetTraits, RelNode rel)  {
        RelMetadataProvider clusterProvider = rel.getCluster().getMetadataProvider();
        JaninoRelMetadataProvider threadProvider = RelMetadataQuery.THREAD_PROVIDERS.get();
        try {
            rel.getCluster().setMetadataProvider(new CachingRelMetadataProvider(IgniteMetadata.METADATA_PROVIDER, planner()));
            RelMetadataQuery.THREAD_PROVIDERS.set(JaninoRelMetadataProvider.of(rel.getCluster().getMetadataProvider()));

            return (T) phase.getProgram(ctx).run(planner(), rel, targetTraits.simplify(), materializations(), latices());
        }
        finally {
            rel.getCluster().setMetadataProvider(clusterProvider);
            RelMetadataQuery.THREAD_PROVIDERS.set(threadProvider);
        }
    }

    /** {@inheritDoc} */
    @Override public JavaTypeFactory getTypeFactory() {
        return typeFactory;
    }

    /** */
    private RelOptPlanner planner() {
        if (planner == null) {
            planner = VolcanoUtils.impatient(new VolcanoPlanner(frameworkConfig.getCostFactory(), ctx));
            planner.setExecutor(rexExecutor);

            for (RelTraitDef<?> def : traitDefs)
                planner.addRelTraitDef(def);
        }

        return planner;
    }

    /** */
    private SqlValidator validator() {
        if (validator == null)
            validator = new IgniteSqlValidator(operatorTable(), createCatalogReader(), typeFactory, conformance());

        return validator;
    }

    /** */
    private SqlConformance conformance() {
        return connectionConfig.conformance();
    }

    /** */
    private SqlOperatorTable operatorTable() {
        return operatorTable;
    }

    /** */
    private RexBuilder createRexBuilder() {
        return new RexBuilder(typeFactory);
    }

    /** */
    private RelBuilder createRelBuilder(RelOptCluster cluster, RelOptSchema schema) {
        return sqlToRelConverterConfig.getRelBuilderFactory().create(cluster, schema);
    }

    /** */
    private CalciteCatalogReader createCatalogReader() {
        return new CalciteCatalogReader(
            CalciteSchema.from(rootSchema(defaultSchema)),
            CalciteSchema.from(defaultSchema).path(null),
            typeFactory, connectionConfig);
    }

    /** */
    private static SchemaPlus rootSchema(SchemaPlus schema) {
        for (; ; ) {
            if (schema.getParentSchema() == null) {
                return schema;
            }
            schema = schema.getParentSchema();
        }
    }

    /** */
    private List<RelOptLattice> latices() {
        return ImmutableList.of(); // TODO
    }

    /** */
    private List<RelOptMaterialization> materializations() {
        return ImmutableList.of(); // TODO
    }
}