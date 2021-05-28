/*
 * Tai-e: A Static Analysis Framework for Java
 *
 * Copyright (C) 2020-- Tian Tan <tiantan@nju.edu.cn>
 * Copyright (C) 2020-- Yue Li <yueli@nju.edu.cn>
 * All rights reserved.
 *
 * Tai-e is only for educational and academic purposes,
 * and any form of commercial use is disallowed.
 * Distribution of Tai-e is disallowed without the approval.
 */

package pascal.taie.analysis.pta.plugin.invokedynamic;

import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.cs.selector.ContextSelector;
import pascal.taie.analysis.pta.core.heap.MockObj;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.PointerFlowEdge;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.InvokeDynamic;
import pascal.taie.ir.exp.MethodHandle;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.MethodRef;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.language.classes.ClassHierarchy;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.classes.StringReps;
import pascal.taie.language.type.ClassType;
import pascal.taie.language.type.Type;
import pascal.taie.util.AnalysisException;
import pascal.taie.util.collection.CollectionUtils;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static pascal.taie.util.collection.MapUtils.addToMapMap;
import static pascal.taie.util.collection.MapUtils.addToMapSet;
import static pascal.taie.util.collection.MapUtils.getMapMap;
import static pascal.taie.util.collection.MapUtils.newMap;

public class LambdaPlugin implements Plugin {

    /**
     * Description for lambda functional objects.
     */
    public static final String LAMBDA_DESC = "LambdaObj";

    /**
     * Description for objects created by lambda constructor.
     */
    public static final String LAMBDA_NEW_DESC = "LambdaConstructedObj";

    private Solver solver;

    private ContextSelector selector;

    private ClassHierarchy hierarchy;

    private CSManager csManager;

    /**
     * Map from method to the lambda functional objects created in the method.
     */
    private final Map<JMethod, Set<MockObj>> lambdaObjs = newMap();

    /**
     * Map from receiver variable to the delayed call edge information
     * when pts of receiver variable is not prepared for dispatching abstract method
     */
    private final Map<Var, Set<DelayedCallEdgeInfo>> delayedCallEdge = newMap();

    /**
     * Map from Invoke (of invokedynamic) and type to mock obj to avoid mocking same objects
     */
    private final Map<Invoke, Map<ClassType, MockObj>> newObjs = newMap();

    @Override
    public void setSolver(Solver solver) {
        this.solver = solver;
        this.selector = solver.getContextSelector();
        this.hierarchy = solver.getHierarchy();
        this.csManager = solver.getCSManager();
    }

    @Override
    public void onNewMethod(JMethod method) {
        extractLambdaMetaFactories(method.getIR()).forEach(invoke -> {
            InvokeDynamic indy = (InvokeDynamic) invoke.getInvokeExp();
            Type type = indy.getMethodType().getReturnType();
            JMethod container = invoke.getContainer();
            // record lambda meta factories of new discovered methods
            addToMapSet(lambdaObjs, container,
                    new MockObj(LAMBDA_DESC, invoke, type, container));
        });
    }

    private static Stream<Invoke> extractLambdaMetaFactories(IR ir) {
        return ir.getStmts()
                .stream()
                .filter(s -> s instanceof Invoke)
                .map(s -> (Invoke) s)
                .filter(LambdaPlugin::isLambdaMetaFactory);
    }

    static boolean isLambdaMetaFactory(Invoke invoke) {
        if (invoke.getInvokeExp() instanceof InvokeDynamic) {
            JMethod bsm = ((InvokeDynamic) invoke.getInvokeExp())
                    .getBootstrapMethodRef().resolve();
            String bsmSig = bsm.getSignature();
            return bsmSig.equals(StringReps.LAMBDA_METAFACTORY) ||
                    bsmSig.equals(StringReps.LAMBDA_ALTMETAFACTORY);
        }
        return false;
    }

    @Override
    public void onNewCSMethod(CSMethod csMethod) {
        JMethod method = csMethod.getMethod();
        Set<MockObj> lambdas = lambdaObjs.get(method);
        if (lambdas != null) {
            Context context = csMethod.getContext();
            lambdas.forEach(lambdaObj -> {
                // propagate lambda functional objects
                Invoke invoke = (Invoke) lambdaObj.getAllocation();
                Var ret = invoke.getResult();
                assert ret != null;
                // here we use full method context as the heap context of
                // lambda object, so that it can be directly used to obtain
                // captured values.
                solver.addVarPointsTo(context, ret, context, lambdaObj);
            });
        }
    }

    @Override
    public void onUnresolvedCall(CSObj recv, Context context, Invoke invoke) {
        if (!isLambdaObj(recv.getObject())) {
            return;
        }
        MockObj lambdaObj = (MockObj) recv.getObject();
        Invoke indyInvoke = (Invoke) lambdaObj.getAllocation();
        InvokeDynamic indy = (InvokeDynamic) indyInvoke.getInvokeExp();
        Context indyCtx = recv.getContext();

        List<Var> actualParams = invoke.getInvokeExp().getArgs();
        List<Var> capturedValues = indy.getArgs();
        Var invokeResult = invoke.getResult();
        CSCallSite csCallSite = csManager.getCSCallSite(context, invoke);
        MethodHandle mh = (MethodHandle) indy.getBootstrapArgs().get(1);
        final JMethod target = mh.getMethodRef().resolve();

        switch (mh.getKind()) {
            case REF_newInvokeSpecial: { // constructor
                JMethod ctor = mh.getMethodRef().resolve();
                ClassType type = ctor.getDeclaringClass().getType();
                // Create mock object (if absent) which represents
                // the newly-allocated object
                MockObj newObj = getMapMap(newObjs, indyInvoke, type);
                if (newObj == null) {
                    // TODO: use heapModel to process mock obj?
                    newObj = new MockObj(LAMBDA_NEW_DESC, indyInvoke, type,
                            indyInvoke.getContainer());
                    addToMapMap(newObjs, indyInvoke, type, newObj);
                }
                // Pass the mock object to LHS variable (if present)
                // TODO: double-check if the hctx is proper
                Context hctx = context;
                if (invokeResult != null) {
                    solver.addVarPointsTo(context, invokeResult, hctx, newObj);
                }
                //  Pass the mock object to 'this' variable of the constructor
                CSObj csNewObj = csManager.getCSObj(hctx, newObj);
                Context ctorCtx = selector.selectContext(csCallSite, csNewObj, ctor);
                solver.addVarPointsTo(ctorCtx, ctor.getIR().getThis(),
                        hctx, newObj);
                // Add call edge to constructor
                addLambdaCallEdge(csCallSite, csNewObj, ctor, indy, indyCtx);
                break;
            }
            case REF_invokeInterface:
            case REF_invokeVirtual:
            case REF_invokeSpecial: {
                Var recvVar;
                Context recvCtx;
                if (capturedValues.isEmpty()) {
                    recvVar = actualParams.get(0);
                    recvCtx = context;
                } else {
                    recvVar = capturedValues.get(0);
                    recvCtx = recv.getContext();
                }
                CSVar csRecvVar = csManager.getCSVar(recvCtx, recvVar);
                solver.getPointsToSetOf(csRecvVar).forEach(recvObj -> {
                    Type rectType = recvObj.getObject().getType();
                    JMethod trueTarget = hierarchy.dispatch(rectType, target.getRef());
                    if (trueTarget != null) {
                        addToMapSet(delayedCallEdge, recvVar,
                                new DelayedCallEdgeInfo(csCallSite, target.getRef(),
                                        recvObj, invokeResult, capturedValues));

                        addLambdaCallEdge(csCallSite, recvObj, trueTarget, indy, indyCtx);
                    }
                });
                break;
            }
            case REF_invokeStatic: {
                addLambdaCallEdge(csCallSite, null, target, indy, indyCtx);
                break;
            }
            default:
                throw new AnalysisException(mh.getKind() + " is not supported");
        }
    }

    private static boolean isLambdaObj(Obj obj) {
        return obj instanceof MockObj &&
                ((MockObj) obj).getDescription().equals(LAMBDA_DESC);
    }

    private void addLambdaCallEdge(
            CSCallSite csCallSite, @Nullable CSObj recv, JMethod callee,
            InvokeDynamic indy, Context indyCtx) {
        Context calleeCtx;
        if (recv != null) {
            calleeCtx = selector.selectContext(csCallSite, recv, callee);
        } else {
            calleeCtx = selector.selectContext(csCallSite, callee);
        }
        LambdaCallEdge callEdge = new LambdaCallEdge(csCallSite,
                csManager.getCSMethod(calleeCtx, callee));
        callEdge.setLambdaParams(csCallSite.getCallSite().getResult(),
                indy.getArgs(), indyCtx);
        solver.addCallEdge(callEdge);
    }

    @Override
    public void onNewCallEdge(Edge<CSCallSite, CSMethod> edge) {
        if (edge instanceof LambdaCallEdge) {
            LambdaCallEdge lambdaCallEdge = (LambdaCallEdge) edge;
            CSCallSite csCallSite = lambdaCallEdge.getCallSite();
            CSMethod csMethod = lambdaCallEdge.getCallee();

            Var invokeResult = lambdaCallEdge.getInvokeResult();
            List<Var> actualParams = csCallSite.getCallSite().getInvokeExp().getArgs();
            JMethod implMethod = csMethod.getMethod();
            List<Var> capturedValues = lambdaCallEdge.getCapturedValues();

            Context callerContext = csCallSite.getContext();
            Context implMethodContext = csMethod.getContext();
            Context lambdaContext = lambdaCallEdge.getLambdaContext();

            // shift flags for passing parameters
            int shiftFlagK = 0;
            int shiftFlagN = 0;
            int capturedCount = capturedValues.size(); // #i
            List<Var> implParams = implMethod.getIR().getParams();

            if (!implMethod.isStatic() && !implMethod.isConstructor()) {
                shiftFlagK = capturedCount == 0 ? 0 : 1;
                shiftFlagN = 1 - shiftFlagK;
            }

            // pass parameters: from actual parameters & from captured values
            if (implParams.size() != 0) {
                for (int i = 0; i + shiftFlagK <= capturedCount && i < capturedValues.size(); i++) {
                    if (i - shiftFlagK < 0) {
                        continue;
                    }
                    solver.addPFGEdge(
                            csManager.getCSVar(lambdaContext, capturedValues.get(i)),
                            csManager.getCSVar(implMethodContext, implParams.get(i - shiftFlagK)),
                            PointerFlowEdge.Kind.PARAMETER_PASSING);
                }

                for (int i = 0; i < actualParams.size(); i++) {
                    int index = capturedCount - (shiftFlagK + shiftFlagN) + i;
                    if (index < 0) {
                        continue;
                    }
                    if (index >= implParams.size()) {
                        break;
                    }
                    solver.addPFGEdge(
                            csManager.getCSVar(callerContext, actualParams.get(i)),
                            csManager.getCSVar(implMethodContext, implParams.get(index)),
                            PointerFlowEdge.Kind.PARAMETER_PASSING);
                }
            }

            // pass return values
            List<Var> returnValues = implMethod.getIR().getReturnVars();
            if (invokeResult != null && !CollectionUtils.isEmpty(returnValues)) {
                CSVar csInvokeResult = csManager.getCSVar(callerContext, invokeResult);
                returnValues.stream()
                        .map(r -> csManager.getCSVar(implMethodContext, r))
                        .forEach(r ->
                                solver.addPFGEdge(r, csInvokeResult, PointerFlowEdge.Kind.RETURN));
            }

            if (shiftFlagK == 1 && !CollectionUtils.isEmpty(capturedValues)) {
                solver.addPFGEdge(
                        csManager.getCSVar(lambdaContext, capturedValues.get(0)),
                        csManager.getCSVar(implMethodContext, implMethod.getIR().getThis()),
                        PointerFlowEdge.Kind.LOCAL_ASSIGN);
            }

            if (shiftFlagN == 1 && !CollectionUtils.isEmpty(actualParams)) {
                solver.addPFGEdge(
                        csManager.getCSVar(callerContext, actualParams.get(0)),
                        csManager.getCSVar(implMethodContext, implMethod.getIR().getThis()),
                        PointerFlowEdge.Kind.LOCAL_ASSIGN);
            }
        }
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        Set<DelayedCallEdgeInfo> callEdgeInfos = delayedCallEdge.get(csVar.getVar());
        if (CollectionUtils.isEmpty(callEdgeInfos)) {
            return;
        }
        for (DelayedCallEdgeInfo info : callEdgeInfos) {
            CSCallSite csCallSite = info.getCSCallSite();
            MethodRef implMethodRef = info.getImplMethodRef();
            JMethod implMethod = null;
            List<Type> types = pts.objects()
                    .map(CSObj::getObject).map(Obj::getType)
                    .collect(Collectors.toList());
            for (Type t : types) {
                JMethod method = hierarchy.dispatch(t, implMethodRef);
                if (method != null) {
                    implMethod = method;
                    break;
                }
            }
            if (implMethod == null) {
                continue;
            }
            Context implContext = selector.selectContext(
                    csCallSite, info.getRecv(), implMethod);
            LambdaCallEdge trueCallEdge = new LambdaCallEdge(
                    csCallSite, csManager.getCSMethod(implContext, implMethod));
            trueCallEdge.setLambdaParams(
                    info.getInvokeResult(),
                    info.getCapturedValues(),
                    info.getRecv().getContext());
            solver.addCallEdge(trueCallEdge);
        }
    }

    private static class DelayedCallEdgeInfo {

        private CSCallSite csCallSite;

        private MethodRef implMethodRef;

        private CSObj recv;

        private Var invokeResult;

        private List<Var> capturedValues;

        public DelayedCallEdgeInfo(CSCallSite csCallSite, MethodRef implMethodRef,
                                   CSObj recv, Var invokeResult, List<Var> capturedValues) {
            this.csCallSite = csCallSite;
            this.implMethodRef = implMethodRef;
            this.recv = recv;
            this.invokeResult = invokeResult;
            this.capturedValues = capturedValues;
        }

        public CSCallSite getCSCallSite() {
            return csCallSite;
        }

        public void setCSCallSite(CSCallSite csCallSite) {
            this.csCallSite = csCallSite;
        }

        public MethodRef getImplMethodRef() {
            return implMethodRef;
        }

        public void setImplMethodRef(MethodRef implMethodRef) {
            this.implMethodRef = implMethodRef;
        }

        public CSObj getRecv() {
            return recv;
        }

        public void setRecv(CSObj recv) {
            this.recv = recv;
        }

        public Var getInvokeResult() {
            return invokeResult;
        }

        public void setInvokeResult(Var invokeResult) {
            this.invokeResult = invokeResult;
        }

        public List<Var> getCapturedValues() {
            return capturedValues;
        }

        public void setCapturedValues(List<Var> capturedValues) {
            this.capturedValues = capturedValues;
        }
    }
}
