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

package org.apache.pig.backend.hadoop.executionengine.tez;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.RawComparator;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.WritableComparator;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.LocalResourceType;
import org.apache.hadoop.yarn.api.records.LocalResourceVisibility;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.pig.LoadFunc;
import org.apache.pig.PigException;
import org.apache.pig.StoreFuncInterface;
import org.apache.pig.backend.executionengine.ExecException;
import org.apache.pig.backend.hadoop.HDataType;
import org.apache.pig.backend.hadoop.datastorage.ConfigurationUtil;
import org.apache.pig.backend.hadoop.executionengine.JobCreationException;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.JobControlCompiler;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigBigDecimalRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigBigIntegerRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigBooleanRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigBytesRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigCombiner;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigDateTimeRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigDoubleRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigFloatRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigInputFormat;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigIntRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigLongRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigOutputFormat;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigTextRawComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.PigTupleSortComparator;
import org.apache.pig.backend.hadoop.executionengine.mapReduceLayer.partitioners.WeightedRangePartitioner;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.PhysicalOperator;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.plans.PhysicalPlan;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLoad;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POLocalRearrange;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POPackage;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.relationalOperators.POStore;
import org.apache.pig.backend.hadoop.executionengine.physicalLayer.util.PlanHelper;
import org.apache.pig.backend.hadoop.executionengine.tez.TezPOPackageAnnotator.LoRearrangeDiscoverer;
import org.apache.pig.data.DataType;
import org.apache.pig.impl.PigContext;
import org.apache.pig.impl.io.FileLocalizer;
import org.apache.pig.impl.io.FileSpec;
import org.apache.pig.impl.io.NullableTuple;
import org.apache.pig.impl.plan.DependencyOrderWalker;
import org.apache.pig.impl.plan.NodeIdGenerator;
import org.apache.pig.impl.plan.OperatorKey;
import org.apache.pig.impl.plan.VisitorException;
import org.apache.pig.impl.util.ObjectSerializer;
import org.apache.pig.impl.util.UDFContext;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.common.TezUtils;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.Edge;
import org.apache.tez.dag.api.EdgeProperty;
import org.apache.tez.dag.api.InputDescriptor;
import org.apache.tez.dag.api.OutputDescriptor;
import org.apache.tez.dag.api.ProcessorDescriptor;
import org.apache.tez.dag.api.Vertex;
import org.apache.tez.mapreduce.combine.MRCombiner;
import org.apache.tez.mapreduce.common.MRInputAMSplitGenerator;
import org.apache.tez.mapreduce.hadoop.MRHelpers;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;
import org.apache.tez.mapreduce.input.MRInput;
import org.apache.tez.mapreduce.output.MROutput;
import org.apache.tez.mapreduce.partition.MRPartitioner;

/**
 * A visitor to construct DAG out of Tez plan.
 */
public class TezDagBuilder extends TezOpPlanVisitor {
    private static final Log log = LogFactory.getLog(TezJobControlCompiler.class);

    private DAG dag;
    private Map<String, LocalResource> localResources;
    private PigContext pc;
    private Configuration globalConf;

    private String scope;
    private NodeIdGenerator nig;

    public TezDagBuilder(PigContext pc, TezOperPlan plan, DAG dag,
            Map<String, LocalResource> localResources) {
        super(plan, new DependencyOrderWalker<TezOperator, TezOperPlan>(plan));
        this.pc = pc;
        this.globalConf = ConfigurationUtil.toConfiguration(pc.getProperties(), true);
        this.localResources = localResources;
        this.dag = dag;
        this.scope = plan.getRoots().get(0).getOperatorKey().getScope();
        this.nig = NodeIdGenerator.getGenerator();
    }

    @Override
    public void visitTezOp(TezOperator tezOp) throws VisitorException {
        // Construct vertex for the current Tez operator
        Vertex to = null;
        try {
            to = newVertex(tezOp);
            dag.addVertex(to);
        } catch (IOException e) {
            throw new VisitorException("Cannot create vertex for "
                    + tezOp.name(), e);
        }

        // Connect the new vertex with predecessor vertices
        TezOperPlan tezPlan = getPlan();
        List<TezOperator> predecessors = tezPlan.getPredecessors(tezOp);
        if (predecessors != null) {
            for (TezOperator predecessor : predecessors) {
                // Since this is a dependency order walker, predecessor vertices
                // must have already been created.
                Vertex from = dag.getVertex(predecessor.name());
                EdgeProperty prop = null;
                try {
                    prop = newEdge(predecessor, tezOp);
                } catch (IOException e) {
                    throw new VisitorException("Cannot create edge from "
                            + predecessor.name() + " to " + tezOp.name(), e);
                }
                Edge edge = new Edge(from, to, prop);
                dag.addEdge(edge);
            }
        }
    }

    /**
     * Return EdgeProperty that connects two vertices.
     *
     * @param from
     * @param to
     * @return EdgeProperty
     * @throws IOException
     */
    private EdgeProperty newEdge(TezOperator from, TezOperator to)
            throws IOException {
        TezEdgeDescriptor edge = to.inEdges.get(from.getOperatorKey());
        PhysicalPlan combinePlan = edge.combinePlan;

        InputDescriptor in = new InputDescriptor(edge.inputClassName);
        OutputDescriptor out = new OutputDescriptor(edge.outputClassName);

        if (!combinePlan.isEmpty()) {
            Configuration conf = new Configuration();
            addCombiner(combinePlan, conf);
            in.setUserPayload(TezUtils.createUserPayloadFromConf(conf));
            out.setUserPayload(TezUtils.createUserPayloadFromConf(conf));
        }

        return new EdgeProperty(edge.dataMovementType, edge.dataSourceType,
                edge.schedulingType, out, in);
    }

    private void addCombiner(PhysicalPlan combinePlan, Configuration conf) throws IOException {
        POPackage combPack = (POPackage)combinePlan.getRoots().get(0);
        setIntermediateInputKeyValue(combPack.getPkgr().getKeyType(), conf);

        POLocalRearrange combRearrange = (POLocalRearrange) combinePlan
                .getLeaves().get(0);
        setIntermediateOutputKeyValue(combRearrange.getKeyType(), conf);

        LoRearrangeDiscoverer lrDiscoverer = new LoRearrangeDiscoverer(
                combinePlan, combPack);
        lrDiscoverer.visit();

        combinePlan.remove(combPack);
        conf.set(TezJobConfig.TEZ_RUNTIME_COMBINER_CLASS,
                MRCombiner.class.getName());
        conf.set(MRJobConfig.COMBINE_CLASS_ATTR,
                PigCombiner.Combine.class.getName());
        conf.setBoolean("mapred.mapper.new-api", true);
        conf.set("pig.pigContext", ObjectSerializer.serialize(pc));
        conf.set("pig.combinePlan", ObjectSerializer.serialize(combinePlan));
        conf.set("pig.combine.package", ObjectSerializer.serialize(combPack));
        conf.set("pig.map.keytype", ObjectSerializer
                .serialize(new byte[] { combRearrange.getKeyType() }));
    }

    private Vertex newVertex(TezOperator tezOp) throws IOException {
        ProcessorDescriptor procDesc = new ProcessorDescriptor(
                tezOp.getProcessorName());

        // Pass physical plans to vertex as user payload.
        Configuration payloadConf = new Configuration();
        // We won't actually use this job, but we need it to talk with the Load
        // Store funcs
        @SuppressWarnings("deprecation")
        Job job = new Job(payloadConf);

        ArrayList<POStore> storeLocations = new ArrayList<POStore>();
        Path tmpLocation = null;

        List<POLoad> loads = processLoads(tezOp, payloadConf, job);
        payloadConf.set("pig.pigContext", ObjectSerializer.serialize(pc));

        payloadConf.set("udf.import.list",
                ObjectSerializer.serialize(PigContext.getPackageImportList()));
        payloadConf.setBoolean("mapred.mapper.new-api", true);
        payloadConf.setClass("mapreduce.inputformat.class",
                PigInputFormat.class, InputFormat.class);

        // We need to remove all the POStores from the exec plan and later add
        // them as outputs of the vertex
        LinkedList<POStore> stores = PlanHelper.getPhysicalOperators(
                tezOp.plan, POStore.class);

        for (POStore st : stores) {
            storeLocations.add(st);
            StoreFuncInterface sFunc = st.getStoreFunc();
            sFunc.setStoreLocation(st.getSFile().getFileName(), job);
        }

        if (stores.size() == 1) {
            POStore st = stores.get(0);
            if (!pc.inIllustrator)
                tezOp.plan.remove(st);

            // set out filespecs
            String outputPathString = st.getSFile().getFileName();
            if (!outputPathString.contains("://")
                    || outputPathString.startsWith("hdfs://")) {
                payloadConf.set("pig.streaming.log.dir", new Path(
                        outputPathString, JobControlCompiler.LOG_DIR)
                        .toString());
            } else {
                String tmpLocationStr = FileLocalizer.getTemporaryPath(pc)
                        .toString();
                tmpLocation = new Path(tmpLocationStr);
                payloadConf.set("pig.streaming.log.dir", new Path(tmpLocation,
                        JobControlCompiler.LOG_DIR).toString());
            }
            payloadConf.set("pig.streaming.task.output.dir", outputPathString);
        } else if (stores.size() > 0) { // multi store case
            log.info("Setting up multi store job");
            String tmpLocationStr = FileLocalizer.getTemporaryPath(pc)
                    .toString();
            tmpLocation = new Path(tmpLocationStr);

            boolean disableCounter = payloadConf.getBoolean(
                    "pig.disable.counter", false);
            if (disableCounter) {
                log.info("Disable Pig custom output counters");
            }
            int idx = 0;
            for (POStore sto : storeLocations) {
                sto.setDisableCounter(disableCounter);
                sto.setMultiStore(true);
                sto.setIndex(idx++);
            }

            payloadConf.set("pig.streaming.log.dir", new Path(tmpLocation,
                    JobControlCompiler.LOG_DIR).toString());
            payloadConf.set("pig.streaming.task.output.dir",
                    tmpLocation.toString());
        }

        if (!pc.inIllustrator) {
            // Unset inputs for POStore, otherwise, exec plan will be
            // unnecessarily deserialized
            for (POStore st : stores) {
                st.setInputs(null);
                st.setParentPlan(null);
            }
            // We put them in the reduce because PigOutputCommitter checks the
            // ID of the task to see if it's a map, and if not, calls the reduce
            // committers.
            payloadConf.set(JobControlCompiler.PIG_MAP_STORES,
                    ObjectSerializer.serialize(new ArrayList<POStore>()));
            payloadConf.set(JobControlCompiler.PIG_REDUCE_STORES,
                    ObjectSerializer.serialize(stores));
        }

        // For all shuffle outputs, configure the classes
        List<PhysicalOperator> leaves = tezOp.plan.getLeaves();
        // TODO: For multiple POLocalRearrange leaves, we need to loop over
        // leaves and set up per shuffle. i.e. SPLIT + multiple GROUP BY with
        // different keys.
        if (leaves.size() == 1 && leaves.get(0) instanceof POLocalRearrange) {
            byte keyType = ((POLocalRearrange)leaves.get(0)).getKeyType();
            setIntermediateOutputKeyValue(keyType, payloadConf);
            payloadConf.set("pig.reduce.key.type", Byte.toString(keyType));
        }

        // Configure the classes for incoming shuffles to this TezOp
        List<PhysicalOperator> roots = tezOp.plan.getRoots();
        if (roots.size() == 1 && roots.get(0) instanceof POPackage) {
            POPackage pack = (POPackage) roots.get(0);

            List<PhysicalOperator> succsList = tezOp.plan.getSuccessors(pack);
            if (succsList != null) {
                succsList = new ArrayList<PhysicalOperator>(succsList);
            }
            byte keyType = pack.getPkgr().getKeyType();
            tezOp.plan.remove(pack);
            payloadConf.set("pig.reduce.package", ObjectSerializer.serialize(pack));
            payloadConf.set("pig.reduce.key.type", Byte.toString(keyType));
            setIntermediateInputKeyValue(keyType, payloadConf);
            POPackage newPack;
            if (tezOp.isUnion()) {
                newPack = new POBroadcastTezLoad(new OperatorKey(scope, nig.getNextNodeId(scope)));
            } else {
                newPack = new POShuffleTezLoad(new OperatorKey(scope, nig.getNextNodeId(scope)), pack);
            }
            tezOp.plan.add(newPack);

            if (succsList != null) {
                for (PhysicalOperator succs : succsList) {
                    tezOp.plan.connect(newPack, succs);
                }
            }

            @SuppressWarnings("rawtypes")
            Class<? extends WritableComparable> keyClass = HDataType
                    .getWritableComparableTypes(pack.getPkgr().getKeyType())
                    .getClass();
            payloadConf.set(
                    TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_INPUT_KEY_CLASS,
                    keyClass.getName());
            payloadConf.set(
                    TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_INPUT_VALUE_CLASS,
                    NullableTuple.class.getName());
            selectInputComparator(payloadConf, pack.getPkgr().getKeyType());
        }

        if(tezOp.isGlobalSort() || tezOp.isLimitAfterSort()){
            if (tezOp.isGlobalSort()) {
                FileSystem fs = FileSystem.get(globalConf);
                Path quantFilePath = new Path(tezOp.getQuantFile() + "/part-r-00000");
                FileStatus fstat = fs.getFileStatus(quantFilePath);
                LocalResource quantFileResource = LocalResource.newInstance(
                        ConverterUtils.getYarnUrlFromPath(fstat.getPath()),
                        LocalResourceType.FILE,
                        LocalResourceVisibility.APPLICATION,
                        fstat.getLen(),
                        fstat.getModificationTime());
                localResources.put(quantFilePath.getName(), quantFileResource);
                payloadConf.set("pig.quantilesFile", fstat.getPath().toString());
                payloadConf.set("pig.sortOrder",
                        ObjectSerializer.serialize(tezOp.getSortOrder()));
                payloadConf.setClass("mapreduce.job.partitioner.class",
                        WeightedRangePartitioner.class,
                        Partitioner.class);
            }
        }

        payloadConf.setClass("mapreduce.outputformat.class",
                PigOutputFormat.class, OutputFormat.class);

        // Serialize the execution plan
        payloadConf.set(PigProcessor.PLAN,
                ObjectSerializer.serialize(tezOp.plan));
        UDFContext.getUDFContext().serialize(payloadConf);

        // Take our assembled configuration and create a vertex
        byte[] userPayload = TezUtils.createUserPayloadFromConf(payloadConf);
        procDesc.setUserPayload(userPayload);
        // Can only set parallelism here if the parallelism isn't derived from
        // splits
        int parallelism = (loads.size() == 0) ? tezOp.requestedParallelism : -1;
        Vertex vertex = new Vertex(tezOp.name(), procDesc, parallelism,
                Resource.newInstance(tezOp.requestedMemory, tezOp.requestedCpu));

        Map<String, String> env = new HashMap<String, String>();
        MRHelpers.updateEnvironmentForMRTasks(globalConf, env, true);
        vertex.setTaskEnvironment(env);

        vertex.setTaskLocalResources(localResources);

        // This could also be reduce, but we need to choose one
        // TODO: Create new or use existing settings that are specifically for
        // Tez.
        vertex.setJavaOpts(MRHelpers.getMapJavaOpts(globalConf));

        // Right now there can only be one of each of these. Will need to be
        // more generic when there can be more.
        for (POLoad ld : loads) {

            // TODO: These should get the globalConf, or a merged version that
            // keeps settings like pig.maxCombinedSplitSize
            vertex.addInput(ld.getOperatorKey().toString(),
                    new InputDescriptor(MRInput.class.getName())
                            .setUserPayload(MRHelpers.createMRInputPayload(
                                    userPayload, null)),
                    MRInputAMSplitGenerator.class);
        }
        if (!stores.isEmpty()) {
            vertex.addOutput("PigOutput",
                    new OutputDescriptor(MROutput.class.getName()));
        }
        return vertex;
    }

    /**
     * Do the final configuration of LoadFuncs and store what goes where. This
     * will need to be changed as the inputs get un-bundled
     *
     * @param tezOp
     * @param conf
     * @param job
     * @return true if any POLoads were found, else false.
     * @throws VisitorException
     * @throws IOException
     */
    private List<POLoad> processLoads(TezOperator tezOp, Configuration conf,
            Job job) throws VisitorException, IOException {
        ArrayList<FileSpec> inp = new ArrayList<FileSpec>();
        ArrayList<List<OperatorKey>> inpTargets = new ArrayList<List<OperatorKey>>();
        ArrayList<String> inpSignatureLists = new ArrayList<String>();
        ArrayList<Long> inpLimits = new ArrayList<Long>();

        List<POLoad> lds = PlanHelper.getPhysicalOperators(tezOp.plan,
                POLoad.class);

        if (lds != null && lds.size() > 0) {
            for (POLoad ld : lds) {
                LoadFunc lf = ld.getLoadFunc();
                lf.setLocation(ld.getLFile().getFileName(), job);

                // Store the inp filespecs
                inp.add(ld.getLFile());
            }
        }

        if (lds != null && lds.size() > 0) {
            for (POLoad ld : lds) {
                // Store the target operators for tuples read
                // from this input
                List<PhysicalOperator> ldSucs = new ArrayList<PhysicalOperator>(
                        tezOp.plan.getSuccessors(ld));
                List<OperatorKey> ldSucKeys = new ArrayList<OperatorKey>();
                if (ldSucs != null) {
                    for (PhysicalOperator operator2 : ldSucs) {
                        ldSucKeys.add(operator2.getOperatorKey());
                    }
                }
                inpTargets.add(ldSucKeys);
                inpSignatureLists.add(ld.getSignature());
                inpLimits.add(ld.getLimit());
                // Remove the POLoad from the plan
                tezOp.plan.remove(ld);
                // Now add the input handling operator for the Tez backend
                // TODO: Move this upstream to the PhysicalPlan generation
                POSimpleTezLoad tezLoad = new POSimpleTezLoad(new OperatorKey(
                        scope, nig.getNextNodeId(scope)));
                tezLoad.setInputKey(ld.getOperatorKey().toString());
                tezOp.plan.add(tezLoad);
                for (PhysicalOperator sucs : ldSucs) {
                    tezOp.plan.connect(tezLoad, sucs);
                }

            }
        }

        conf.set("pig.inputs", ObjectSerializer.serialize(inp));
        conf.set("pig.inpTargets", ObjectSerializer.serialize(inpTargets));
        conf.set("pig.inpSignatures", ObjectSerializer.serialize(inpSignatureLists));
        conf.set("pig.inpLimits", ObjectSerializer.serialize(inpLimits));

        return lds;
    }

    @SuppressWarnings("rawtypes")
    private void setIntermediateInputKeyValue(byte keyType, Configuration conf)
            throws JobCreationException, ExecException {
        Class<? extends WritableComparable> keyClass = HDataType
                .getWritableComparableTypes(keyType).getClass();
        conf.set(TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_INPUT_KEY_CLASS,
                keyClass.getName());
        conf.set(TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_INPUT_VALUE_CLASS,
                NullableTuple.class.getName());
        selectInputComparator(conf, keyType);
    }

    @SuppressWarnings("rawtypes")
    private void setIntermediateOutputKeyValue(byte keyType, Configuration conf)
            throws JobCreationException, ExecException {
        Class<? extends WritableComparable> keyClass = HDataType
                .getWritableComparableTypes(keyType).getClass();
        conf.set(TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_OUTPUT_KEY_CLASS,
                keyClass.getName());
        conf.set(TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_OUTPUT_VALUE_CLASS,
                NullableTuple.class.getName());
        conf.set(TezJobConfig.TEZ_RUNTIME_PARTITIONER_CLASS,
                MRPartitioner.class.getName());
        selectOutputComparator(keyType, conf);
    }

    static Class<? extends WritableComparator> comparatorForKeyType(byte keyType)
            throws JobCreationException {
        // TODO: Handle sorting like in JobControlCompiler

        switch (keyType) {
        case DataType.BOOLEAN:
            return PigBooleanRawComparator.class;

        case DataType.INTEGER:
            return PigIntRawComparator.class;

        case DataType.BIGINTEGER:
            return PigBigIntegerRawComparator.class;

        case DataType.BIGDECIMAL:
            return PigBigDecimalRawComparator.class;

        case DataType.LONG:
            return PigLongRawComparator.class;

        case DataType.FLOAT:
            return PigFloatRawComparator.class;

        case DataType.DOUBLE:
            return PigDoubleRawComparator.class;

        case DataType.DATETIME:
            return PigDateTimeRawComparator.class;

        case DataType.CHARARRAY:
            return PigTextRawComparator.class;

        case DataType.BYTEARRAY:
            return PigBytesRawComparator.class;

        case DataType.MAP:
            int errCode = 1068;
            String msg = "Using Map as key not supported.";
            throw new JobCreationException(msg, errCode, PigException.INPUT);

        case DataType.TUPLE:
            return PigTupleSortComparator.class;

        case DataType.BAG:
            errCode = 1068;
            msg = "Using Bag as key not supported.";
            throw new JobCreationException(msg, errCode, PigException.INPUT);

        default:
            errCode = 2036;
            msg = "Unhandled key type " + DataType.findTypeName(keyType);
            throw new JobCreationException(msg, errCode, PigException.BUG);
        }
    }

    void selectInputComparator(Configuration conf, byte keyType)
            throws JobCreationException {
        // TODO: Handle sorting like in JobControlCompiler
        conf.setClass(
                TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_INPUT_KEY_COMPARATOR_CLASS,
                comparatorForKeyType(keyType), RawComparator.class);
    }

    void selectOutputComparator(byte keyType, Configuration conf)
            throws JobCreationException {
        // TODO: Handle sorting like in JobControlCompiler
        conf.setClass(
                TezJobConfig.TEZ_RUNTIME_INTERMEDIATE_OUTPUT_KEY_COMPARATOR_CLASS,
                comparatorForKeyType(keyType), RawComparator.class);
    }
}
