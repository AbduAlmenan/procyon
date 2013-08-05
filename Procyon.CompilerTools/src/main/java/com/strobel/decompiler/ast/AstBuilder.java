/*
 * AstBuilder.java
 *
 * Copyright (c) 2013 Mike Strobel
 *
 * This source code is based on Mono.Cecil from Jb Evain, Copyright (c) Jb Evain;
 * and ILSpy/ICSharpCode from SharpDevelop, Copyright (c) AlphaSierraPapa.
 *
 * This source code is subject to terms and conditions of the Apache License, Version 2.0.
 * A copy of the license can be found in the License.html file at the root of this distribution.
 * By using this source code in any fashion, you are agreeing to be bound by the terms of the
 * Apache License, Version 2.0.
 *
 * You must not remove this notice, or any other, from this software.
 */

package com.strobel.decompiler.ast;

import com.strobel.annotations.NotNull;
import com.strobel.assembler.flowanalysis.ControlFlowEdge;
import com.strobel.assembler.flowanalysis.ControlFlowGraph;
import com.strobel.assembler.flowanalysis.ControlFlowGraphBuilder;
import com.strobel.assembler.flowanalysis.ControlFlowNode;
import com.strobel.assembler.flowanalysis.ControlFlowNodeType;
import com.strobel.assembler.flowanalysis.JumpType;
import com.strobel.assembler.ir.*;
import com.strobel.assembler.metadata.*;
import com.strobel.core.ArrayUtilities;
import com.strobel.core.MutableInteger;
import com.strobel.core.Predicate;
import com.strobel.core.StringUtilities;
import com.strobel.core.StrongBox;
import com.strobel.core.VerifyArgument;
import com.strobel.decompiler.DecompilerContext;
import com.strobel.decompiler.InstructionHelper;

import java.util.*;

import static com.strobel.core.CollectionUtilities.*;
import static com.strobel.decompiler.ast.PatternMatching.match;
import static java.lang.String.format;

public final class AstBuilder {
    private final static AstCode[] CODES = AstCode.values();
    private final static StackSlot[] EMPTY_STACK = new StackSlot[0];
    private final static ByteCode[] EMPTY_DEFINITIONS = new ByteCode[0];

    private final Map<ExceptionHandler, ByteCode> _loadExceptions = new LinkedHashMap<>();
    private final Set<Instruction> _removed = new LinkedHashSet<>();
    private Map<Instruction, Instruction> _originalInstructionMap;
    private ControlFlowGraph _cfg;
    private InstructionCollection _instructions;
    private List<ExceptionHandler> _exceptionHandlers;
    private MethodBody _body;
    private boolean _optimize;
    private DecompilerContext _context;
    private CoreMetadataFactory _factory;

    public static List<Node> build(final MethodBody body, final boolean optimize, final DecompilerContext context) {
        final AstBuilder builder = new AstBuilder();

        builder._body = VerifyArgument.notNull(body, "body");
        builder._optimize = optimize;
        builder._context = VerifyArgument.notNull(context, "context");

        if (body.getInstructions().isEmpty()) {
            return Collections.emptyList();
        }

        builder._instructions = copyInstructions(body.getInstructions());

        final InstructionCollection oldInstructions = body.getInstructions();
        final InstructionCollection newInstructions = builder._instructions;

        builder._originalInstructionMap = new IdentityHashMap<>();

        for (int i = 0; i < newInstructions.size(); i++) {
            builder._originalInstructionMap.put(newInstructions.get(i), oldInstructions.get(i));
        }

        builder._exceptionHandlers = remapHandlers(body.getExceptionHandlers(), builder._instructions);

        builder.removeSelfHandlingFinallyHandlers();
        builder.removeInlinedFinallyCode();
        builder.trimAggressiveCatchBlocks();
        builder.pruneExceptionHandlers();

        builder._cfg = ControlFlowGraphBuilder.build(builder._instructions, builder._exceptionHandlers);
        builder._cfg.computeDominance();
        builder._cfg.computeDominanceFrontier();

        final List<ByteCode> byteCode = builder.performStackAnalysis();

        @SuppressWarnings("UnnecessaryLocalVariable")
        final List<Node> ast = builder.convertToAst(
            byteCode,
            new LinkedHashSet<>(builder._exceptionHandlers),
            0,
            new MutableInteger(byteCode.size())
        );

        return ast;
    }

    private final static class HandlerInfo {
        final ExceptionHandler handler;
        final ControlFlowNode head;
        final ControlFlowNode tail;
        final List<ControlFlowNode> tryNodes;
        final List<ControlFlowNode> handlerNodes;

        private HandlerInfo(
            final ExceptionHandler handler,
            final ControlFlowNode head,
            final ControlFlowNode tail,
            final List<ControlFlowNode> tryNodes,
            final List<ControlFlowNode> handlerNodes) {

            this.handler = handler;
            this.head = head;
            this.tail = tail;
            this.tryNodes = tryNodes;
            this.handlerNodes = handlerNodes;
        }
    }

    private static Set<ControlFlowNode> findDominatedNodes(final Collection<ControlFlowNode> scope, final ControlFlowNode head) {
        return findDominatedNodes(scope, head, false);
    }

    private static Set<ControlFlowNode> findDominatedNodes(final Collection<ControlFlowNode> scope, final ControlFlowNode head, final boolean normalEdgesOnly) {
        final Set<ControlFlowNode> agenda = new LinkedHashSet<>();
        final Set<ControlFlowNode> result = new LinkedHashSet<>();

        agenda.add(head);

        while (!agenda.isEmpty()) {
            final ControlFlowNode addNode = agenda.iterator().next();

            agenda.remove(addNode);

            if (scope.contains(addNode) && head.dominates(addNode) && result.add(addNode)) {
                final List<ControlFlowEdge> outgoing = addNode.getOutgoing();

                for (final ControlFlowEdge edge : outgoing) {
                    if (!normalEdgesOnly || edge.getType() == JumpType.Normal) {
                        agenda.add(edge.getTarget());
                    }
                }
            }
        }

        return result;
    }

    private boolean areAllRemoved(final Instruction start, final Instruction end) {
        for (Instruction p = start; p != null && p.getOffset() < end.getEndOffset(); p = p.getNext()) {
//            if (p.getOpCode() != OpCode.NOP) {
//                return false;
//            }
            if (!_removed.contains(p)) {
                return false;
            }
        }
        return true;
    }

    private static boolean opCodesMatch(final Instruction tail1, final Instruction tail2, final int count) {
        int i = 0;

        for (Instruction p1 = tail1, p2 = tail2;
             p1 != null && p2 != null && i < count;
             p1 = p1.getPrevious(), p2 = p2.getPrevious(), i++) {

            final OpCode c1 = p1.getOpCode();
            final OpCode c2 = p2.getOpCode();

            if (c1.isLoad()) {
                if (!c2.isLoad() || c2.getStackBehaviorPush() != c1.getStackBehaviorPush()) {
                    return false;
                }
            }
            else if (c1.isStore()) {
                if (!c2.isStore() || c2.getStackBehaviorPop() != c1.getStackBehaviorPop()) {
                    return false;
                }
            }
            else if (c1 != p2.getOpCode()) {
                return false;
            }
        }

        return true;
    }

    private static ControlFlowNode lastNode(final Collection<ControlFlowNode> nodes) {
        ControlFlowNode last = null;

        for (final ControlFlowNode node : nodes) {
            if (last == null || node.getBlockIndex() > last.getBlockIndex()) {
                last = node;
            }
        }

        return last;
    }

    private void removeInlinedFinallyCode() {
        final InstructionCollection instructions = _instructions;
        final List<ExceptionHandler> handlers = _exceptionHandlers;
        final ControlFlowGraph cfg = ControlFlowGraphBuilder.build(instructions, handlers);

        cfg.computeDominance();
        cfg.computeDominanceFrontier();

        final Map<Instruction, ControlFlowNode> nodeMap = new IdentityHashMap<>();
        final Set<ExceptionHandler> finallyHandlers = new LinkedHashSet<>();
        final Map<ExceptionHandler, HandlerInfo> handlerMap = new IdentityHashMap<>();
        final Set<ControlFlowNode> processedNodes = new LinkedHashSet<>();
        final Map<Instruction, Instruction> remappedJumps = new IdentityHashMap<>();

        for (final ControlFlowNode node : cfg.getNodes()) {
            if (node.getNodeType() != ControlFlowNodeType.Normal) {
                continue;
            }

            for (Instruction p = node.getStart();
                 p != null && p.getOffset() < node.getEnd().getEndOffset();
                 p = p.getNext()) {

                nodeMap.put(p, node);
            }
        }

        final Set<ControlFlowNode> allNodes = new LinkedHashSet<>(cfg.getNodes());
        final Set<ControlFlowNode> allFinallyNodes = new LinkedHashSet<>();

        for (final ExceptionHandler handler : handlers) {
            final ControlFlowNode head = nodeMap.get(handler.getHandlerBlock().getFirstInstruction());
            final ControlFlowNode tail = nodeMap.get(handler.getHandlerBlock().getLastInstruction());
            final ControlFlowNode tryHead = nodeMap.get(handler.getTryBlock().getFirstInstruction());
            final List<ControlFlowNode> tryNodes = new ArrayList<>(findDominatedNodes(allNodes, tryHead, true));
            final List<ControlFlowNode> handlerNodes = new ArrayList<>();

            for (int i = head.getBlockIndex(); i <= tail.getBlockIndex(); i++) {
                handlerNodes.add(cfg.getNodes().get(i));
            }

            final HandlerInfo handlerInfo = new HandlerInfo(handler, head, tail, tryNodes, handlerNodes);

            handlerMap.put(handler, handlerInfo);

            if (handler.isFinally()) {
                finallyHandlers.add(handler);
                allFinallyNodes.addAll(handlerNodes);
            }
        }

        for (final ExceptionHandler handler : finallyHandlers) {
            final HandlerInfo handlerInfo = handlerMap.get(handler);
            final List<ExceptionHandler> siblings = new ArrayList<>();
            final List<ControlFlowNode> successors = toList(handlerInfo.tail.getSuccessors());
            final ExceptionBlock finallyBlock = handler.getHandlerBlock();

            for (final ExceptionHandler exceptionHandler : _exceptionHandlers) {
                if (exceptionHandler.isCatch() &&
                    exceptionHandler.getTryBlock().equals(handler.getTryBlock())) {

                    siblings.add(exceptionHandler);
                }
            }

            Instruction first = finallyBlock.getFirstInstruction();
            Instruction last = finallyBlock.getLastInstruction();

            if (first.getOpCode().isStore() &&
                last.getOpCode() == OpCode.ATHROW &&
                last.getPrevious() != null &&
                last.getPrevious() != first &&
                last.getPrevious().getOpCode().isLoad() &&
                InstructionHelper.getLoadOrStoreSlot(first) == InstructionHelper.getLoadOrStoreSlot(last.getPrevious())) {

                first = first.getNext();
                last = last.getPrevious().getPrevious();
            }
            else {
                if (first.getOpCode().isStore() || first.getOpCode() == OpCode.POP) {
                    first = first.getNext();
                }
                if (last.getOpCode().isUnconditionalBranch()) {
                    last = last.getPrevious();
                }
            }

            if (first == null || last == null) {
                continue;
            }

            int instructionCount = 1;

            for (Instruction p = first.getNext(); p != null && p.getOffset() < last.getEndOffset(); p = p.getNext()) {
                ++instructionCount;
            }

            final ControlFlowNode lastTryNode = last(handlerInfo.tryNodes);
            final ControlFlowNode tryHead = nodeMap.get(handler.getTryBlock().getFirstInstruction());
            final ControlFlowNode finallyTail = nodeMap.get(handler.getHandlerBlock().getLastInstruction());
            final List<ControlFlowNode> additionalTargets = new ArrayList<>();

            for (final ControlFlowNode node : lastTryNode.getDominatorTreeChildren()) {
                if (node.getNodeType() == ControlFlowNodeType.CatchHandler) {
                    additionalTargets.add(node);
                }
            }

            if (lastTryNode.precedes(cfg.getRegularExit()) ||
                lastTryNode.precedes(cfg.getRegularExit())) {

                additionalTargets.add(lastTryNode);
            }

            for (final ControlFlowNode n : new ControlFlowNode[] { cfg.getRegularExit(), cfg.getExceptionalExit() }) {
                if (successors.contains(n)) {
                }
            }

/*
            if (successors.contains(cfg.getExceptionalExit())) {
                for (final ControlFlowEdge edge : lastTryNode.getOutgoing()) {
                    if (edge.getType() == JumpType.Normal || edge.getType() == JumpType.LeaveTry) {
                        final ControlFlowNode target = edge.getTarget();

                        if (target.getNodeType() == ControlFlowNodeType.Normal) {
                            additionalTargets.add(target);
                        }
                    }
                }
            }
*/

            for (final ControlFlowNode successor : successors) {
                if (allFinallyNodes.contains(successor)) {
                    continue;
                }

                final List<ControlFlowNode> toProcess = new ArrayList<>(additionalTargets);

                for (ControlFlowNode predecessor : successor.getPredecessors()) {
//                    final ExceptionHandler pHandler = predecessor.getExceptionHandler();
//
//                    if (pHandler != null) {
//                        if (pHandler.isFinally()) {
//                            continue;
//                        }
//
//                        predecessor = handlerMap.get(pHandler).tail;
//                    }

                    if (/*predecessor.getNodeType() == ControlFlowNodeType.Normal &&*/
                        !allFinallyNodes.contains(predecessor)) {

                        final ControlFlowNode p = predecessor;
                        final boolean process = tryHead.dominates(predecessor) ||
                                                any(
                                                    siblings,
                                                    new Predicate<ExceptionHandler>() {
                                                        @Override
                                                        public boolean test(final ExceptionHandler handler) {
                                                            return handlerMap.get(handler).head.dominates(p);
                                                        }
                                                    }
                                                );

                        if (process) {
                            toProcess.add(predecessor);
                        }
                    }
                }

                for (ControlFlowNode node : toProcess) {
                    final ExceptionHandler nodeHandler = node.getExceptionHandler();

                    if (nodeHandler != null) {
                        node = nodeMap.get(nodeHandler.getHandlerBlock().getLastInstruction());
                    }

                    if (processedNodes.contains(node) || allFinallyNodes.contains(node)) {
                        continue;
                    }

                    Instruction tail = node.getEnd();
                    Instruction newJumpTarget = null;
                    boolean isLeave = false;
                    boolean tryPrevious = false;

                    if (tail.getOpCode().isUnconditionalBranch()) {
                        switch (tail.getOpCode()) {
                            case GOTO:
                            case GOTO_W:
                            case RETURN:
                                newJumpTarget = tail;
                                if (newJumpTarget.getOpCode() == OpCode.GOTO || newJumpTarget.getOpCode() == OpCode.GOTO_W) {
                                    newJumpTarget = newJumpTarget.getOperand(0);
                                }
                                tail = tail.getPrevious();
                                break;

                            case IRETURN:
                            case LRETURN:
                            case FRETURN:
                            case DRETURN:
                            case ARETURN:
                                newJumpTarget = tail;
                                if (newJumpTarget.getOpCode() == OpCode.GOTO || newJumpTarget.getOpCode() == OpCode.GOTO_W) {
                                    newJumpTarget = newJumpTarget.getOperand(0);
                                }
                                tail = tail.getPrevious();
                                if (finallyTail.getEnd().getOpCode().getFlowControl() == FlowControl.Return) {
                                    tail = tail.getPrevious();
                                }
                                else {
                                    tryPrevious = true;
                                }
                                break;

                            case ATHROW:
                                tail = tail.getPrevious();
                                if (finallyTail.getEnd().getOpCode() == OpCode.ATHROW) {
                                    isLeave = true;
                                }
                                break;
                        }
                    }

                    if (!opCodesMatch(last, tail, instructionCount)) {
                        if (!tryPrevious || !opCodesMatch(last, tail = tail.getPrevious(), instructionCount)) {
                            continue;
                        }
                    }

                    if (newJumpTarget == null) {
                        Instruction head = tail;
                        for (int i = 1; i < instructionCount; i++) {
                            head = head.getPrevious();
                        }
                        newJumpTarget = head;
                    }

                    for (int i = 0; i < instructionCount; i++) {
//                        tail.setOperand(null);
//                        tail.setOpCode(OpCode.NOP);
                        _removed.add(tail);
                        remappedJumps.put(tail, newJumpTarget);
                        tail = tail.getPrevious();
                    }

                    if (isLeave) {
                        if (tail != null &&
                            tail.getOpCode().isStore() &&
                            !_body.getMethod().getReturnType().isVoid()) {

                            final Instruction load = InstructionHelper.reverseLoadOrStore(tail);
                            final Instruction returnSite = node.getEnd();
                            final Instruction loadSite = returnSite.getPrevious();

                            loadSite.setOpCode(load.getOpCode());

                            if (load.getOperandCount() == 1) {
                                loadSite.setOperand(load.getOperand(0));
                            }

                            switch (load.getOpCode().name().charAt(0)) {
                                case 'I':
                                    returnSite.setOpCode(OpCode.IRETURN);
                                    break;
                                case 'L':
                                    returnSite.setOpCode(OpCode.LRETURN);
                                    break;
                                case 'F':
                                    returnSite.setOpCode(OpCode.FRETURN);
                                    break;
                                case 'D':
                                    returnSite.setOpCode(OpCode.DRETURN);
                                    break;
                                case 'A':
                                    returnSite.setOpCode(OpCode.ARETURN);
                                    break;
                            }

                            returnSite.setOperand(null);

                            _removed.remove(loadSite);
                            _removed.remove(returnSite);
                        }
                        else {
                            node.getEnd().setOpCode(OpCode.NOP);
                            node.getEnd().setOperand(null);
                        }
                    }

                    processedNodes.add(node);
                }
            }
        }

        remapJumps(remappedJumps);

        for (final Instruction p : _removed) {
            p.setOpCode(OpCode.NOP);
            p.setOperand(null);
        }
    }

    private void updateHandlersForRemovedInstruction(final Set<Instruction> toRemove, final Instruction instruction) {
        for (int i = 0; i < _exceptionHandlers.size(); i++) {
            ExceptionHandler handler = _exceptionHandlers.get(i);

            Instruction first = handler.getHandlerBlock().getFirstInstruction();
            Instruction last = handler.getHandlerBlock().getLastInstruction();

            boolean changed = false;

            if (first == instruction) {
                while (first != null &&
                       toRemove.contains(first) &&
                       first.getOffset() < last.getEndOffset()) {

                    first = first.getNext();
                    changed = true;
                }
            }

            if (first != null && last == instruction) {
                while (last != null &&
                       toRemove.contains(last) &&
                       last.getOffset() > first.getOffset()) {

                    last = last.getPrevious();
                    changed = true;
                }
            }

            if (changed) {
                if (first == null ||
                    last == null ||
                    toRemove.contains(first) ||
                    toRemove.contains(last)) {

                    _exceptionHandlers.remove(i--);
                }
                else if (handler.isCatch()) {
                    handler = ExceptionHandler.createCatch(
                        handler.getTryBlock(),
                        new ExceptionBlock(first, last),
                        handler.getCatchType()
                    );

                    _exceptionHandlers.set(i, handler);
                }
                else {
                    handler = ExceptionHandler.createFinally(
                        handler.getTryBlock(),
                        new ExceptionBlock(first, last)
                    );

                    _exceptionHandlers.set(i, handler);
                }
            }
        }
    }

    private static List<ExceptionHandler> remapHandlers(final List<ExceptionHandler> handlers, final InstructionCollection instructions) {
        final List<ExceptionHandler> newHandlers = new ArrayList<>();

        for (final ExceptionHandler handler : handlers) {
            final ExceptionBlock oldTry = handler.getTryBlock();
            final ExceptionBlock oldHandler = handler.getHandlerBlock();

            final ExceptionBlock newTry = new ExceptionBlock(
                instructions.atOffset(oldTry.getFirstInstruction().getOffset()),
                instructions.atOffset(oldTry.getLastInstruction().getOffset())
            );

            final ExceptionBlock newHandler = new ExceptionBlock(
                instructions.atOffset(oldHandler.getFirstInstruction().getOffset()),
                instructions.atOffset(oldHandler.getLastInstruction().getOffset())
            );

            if (handler.isCatch()) {
                newHandlers.add(
                    ExceptionHandler.createCatch(
                        newTry,
                        newHandler,
                        handler.getCatchType()
                    )
                );
            }
            else {
                newHandlers.add(
                    ExceptionHandler.createFinally(
                        newTry,
                        newHandler
                    )
                );
            }
        }

        return newHandlers;
    }

    private static InstructionCollection copyInstructions(final InstructionCollection instructions) {
        final InstructionCollection instructionsCopy = new InstructionCollection();

        for (final Instruction instruction : instructions) {
            final Instruction copy = new Instruction(instruction.getOffset(), instruction.getOpCode());

            if (instruction.getOperandCount() > 1) {
                final Object[] operands = new Object[instruction.getOperandCount()];

                for (int i = 0; i < operands.length; i++) {
                    operands[i] = instruction.getOperand(i);
                }

                copy.setOperand(operands);
            }
            else {
                copy.setOperand(instruction.getOperand(0));
            }

            copy.setLabel(instruction.getLabel());

            instructionsCopy.add(copy);
        }

        for (final Instruction instruction : instructionsCopy) {
            if (!instruction.hasOperand()) {
                continue;
            }

            final Object operand = instruction.getOperand(0);

            if (operand instanceof Instruction) {
                instruction.setOperand(instructionsCopy.atOffset(((Instruction) operand).getOffset()));
            }
            else if (operand instanceof SwitchInfo) {
                final SwitchInfo oldOperand = (SwitchInfo) operand;

                final Instruction oldDefault = oldOperand.getDefaultTarget();
                final Instruction newDefault = instructionsCopy.atOffset(oldDefault.getOffset());

                final Instruction[] oldTargets = oldOperand.getTargets();
                final Instruction[] newTargets = new Instruction[oldTargets.length];

                for (int i = 0; i < newTargets.length; i++) {
                    newTargets[i] = instructionsCopy.atOffset(oldTargets[i].getOffset());
                }

                final SwitchInfo newOperand = new SwitchInfo(oldOperand.getKeys(), newDefault, newTargets);

                instruction.setOperand(newOperand);
            }
        }

        return instructionsCopy;
    }

    private void remapJumps(final Map<Instruction, Instruction> remappedJumps) {
        for (final Instruction instruction : _instructions) {
            if (_removed.contains(instruction)) {
                continue;
            }

            if (instruction.hasLabel()) {
                instruction.getLabel().setIndex(instruction.getOffset());
            }

            if (instruction.getOperandCount() == 0) {
                continue;
            }

            final Object operand = instruction.getOperand(0);

            if (operand instanceof Instruction) {
                final Instruction oldTarget = (Instruction) operand;
                final Instruction newTarget = remappedJumps.get(oldTarget);

                if (newTarget != null) {
                    if (newTarget == instruction) {
                        instruction.setOpCode(OpCode.NOP);
                        instruction.setOperand(null);
                    }
                    else {
                        instruction.setOperand(newTarget);

                        if (!newTarget.hasLabel()) {
                            newTarget.setLabel(new com.strobel.assembler.metadata.Label(newTarget.getOffset()));
                        }
                    }
                }
            }
            else if (operand instanceof SwitchInfo) {
                final SwitchInfo oldOperand = (SwitchInfo) operand;

                final Instruction oldDefault = oldOperand.getDefaultTarget();
                final Instruction newDefault = remappedJumps.get(oldDefault);

                if (newDefault != null && !newDefault.hasLabel()) {
                    newDefault.setLabel(new com.strobel.assembler.metadata.Label(newDefault.getOffset()));
                }

                final Instruction[] oldTargets = oldOperand.getTargets();

                Instruction[] newTargets = null;

                for (int i = 0; i < oldTargets.length; i++) {
                    final Instruction newTarget = remappedJumps.get(oldTargets[i]);

                    if (newTarget != null) {
                        if (newTargets == null) {
                            newTargets = Arrays.copyOf(oldTargets, oldTargets.length);
                        }

                        newTargets[i] = newTarget;

                        if (!newTarget.hasLabel()) {
                            newTarget.setLabel(new com.strobel.assembler.metadata.Label(newTarget.getOffset()));
                        }
                    }
                }

                if (newDefault != null || newTargets != null) {
                    final SwitchInfo newOperand = new SwitchInfo(
                        oldOperand.getKeys(),
                        newDefault != null ? newDefault : oldDefault,
                        newTargets != null ? newTargets : oldTargets
                    );

                    instruction.setOperand(newOperand);
                }
            }
        }
    }

    @SuppressWarnings("ConstantConditions")
    private void pruneExceptionHandlers() {
        final List<ExceptionHandler> handlers = _exceptionHandlers;

        closeTryHandlerGaps();
        mergeSharedHandlers(handlers);
        alignFinallyBlocksWithSiblingCatchBlocks(handlers);

        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);
            final ExceptionBlock tryBlock = handler.getTryBlock();
            final List<ExceptionHandler> siblings = findHandlers(tryBlock, handlers);
            final ExceptionHandler firstSibling = first(siblings);
            final ExceptionBlock firstHandler = firstSibling.getHandlerBlock();
            final Instruction desiredEndTry = firstHandler.getFirstInstruction().getPrevious();

            for (int j = 0; j < siblings.size(); j++) {
                ExceptionHandler sibling = siblings.get(j);

                if (handler.getTryBlock().getLastInstruction() != desiredEndTry) {
                    final int index = handlers.indexOf(sibling);

                    if (sibling.isCatch()) {
                        handlers.set(
                            index,
                            ExceptionHandler.createCatch(
                                new ExceptionBlock(
                                    tryBlock.getFirstInstruction(),
                                    desiredEndTry
                                ),
                                sibling.getHandlerBlock(),
                                sibling.getCatchType()
                            )
                        );
                    }
                    else {
                        handlers.set(
                            index,
                            ExceptionHandler.createFinally(
                                new ExceptionBlock(
                                    tryBlock.getFirstInstruction(),
                                    desiredEndTry
                                ),
                                sibling.getHandlerBlock()
                            )
                        );
                    }

                    sibling = handlers.get(index);
                    siblings.set(j, sibling);
                }
            }
        }

        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);

            if (!handler.isFinally()) {
                continue;
            }

            final ExceptionBlock tryBlock = handler.getTryBlock();
            final List<ExceptionHandler> siblings = findHandlers(tryBlock, handlers);

            for (int j = 0; j < siblings.size(); j++) {
                final ExceptionHandler sibling = siblings.get(j);

                if (sibling.isCatch() && j < siblings.size() - 1) {
                    final ExceptionHandler nextSibling = siblings.get(j + 1);

                    if (sibling.getHandlerBlock().getLastInstruction() !=
                        nextSibling.getHandlerBlock().getFirstInstruction().getPrevious()) {

                        final int index = handlers.indexOf(sibling);

                        handlers.set(
                            index,
                            ExceptionHandler.createCatch(
                                sibling.getTryBlock(),
                                new ExceptionBlock(
                                    sibling.getHandlerBlock().getFirstInstruction(),
                                    nextSibling.getHandlerBlock().getFirstInstruction().getPrevious()
                                ),
                                sibling.getCatchType()
                            )
                        );

                        siblings.set(j, handlers.get(j));
                    }
                }
            }
        }

    outer:
        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);

            if (!handler.isFinally()) {
                continue;
            }

            final ExceptionBlock tryBlock = handler.getTryBlock();
            final List<ExceptionHandler> siblings = findHandlers(tryBlock, handlers);

            for (final ExceptionHandler sibling : siblings) {
                if (sibling == handler || sibling.isFinally()) {
                    continue;
                }

                for (int j = 0; j < handlers.size(); j++) {
                    final ExceptionHandler e = handlers.get(j);

                    if (e == handler || e == sibling || !e.isFinally()) {
                        continue;
                    }

                    if (e.getTryBlock().getFirstInstruction() == sibling.getHandlerBlock().getFirstInstruction() &&
                        e.getHandlerBlock().equals(handler.getHandlerBlock())) {

                        handlers.remove(j);

                        final int removeIndex = j--;

                        if (removeIndex < i) {
                            --i;
                            continue outer;
                        }
                    }
                }
            }
        }

        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);

            if (!handler.isFinally()) {
                continue;
            }

            final ExceptionBlock tryBlock = handler.getTryBlock();
            final ExceptionBlock handlerBlock = handler.getHandlerBlock();

            for (int j = 0; j < handlers.size(); j++) {
                final ExceptionHandler other = handlers.get(j);

                if (other != handler &&
                    other.isFinally() &&
                    other.getHandlerBlock().equals(handlerBlock) &&
                    tryBlock.contains(other.getTryBlock()) &&
                    tryBlock.getLastInstruction() == other.getTryBlock().getLastInstruction()) {

                    handlers.remove(j);

                    if (j < i) {
                        --i;
                        break;
                    }

                    --j;
                }
            }
        }

        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);
            final ExceptionBlock tryBlock = handler.getTryBlock();
            final ExceptionHandler firstHandler = findFirstHandler(tryBlock, handlers);
            final ExceptionBlock firstHandlerBlock = firstHandler.getHandlerBlock();
            final Instruction firstAfterTry = tryBlock.getLastInstruction().getNext();
            final Instruction firstInHandler = firstHandlerBlock.getFirstInstruction();
            final Instruction lastBeforeHandler = firstInHandler.getPrevious();

            if (firstAfterTry != firstInHandler &&
                firstAfterTry != null &&
                lastBeforeHandler != null) {

                ExceptionBlock newTryBlock = null;

                final FlowControl flowControl = lastBeforeHandler.getOpCode().getFlowControl();

                if (flowControl == FlowControl.Branch ||
                    flowControl == FlowControl.Return && lastBeforeHandler.getOpCode() == OpCode.RETURN) {

                    if (lastBeforeHandler == firstAfterTry) {
                        newTryBlock = new ExceptionBlock(tryBlock.getFirstInstruction(), lastBeforeHandler);
                    }
                }
                else if (flowControl == FlowControl.Throw ||
                         flowControl == FlowControl.Return && lastBeforeHandler.getOpCode() != OpCode.RETURN) {

                    if (lastBeforeHandler.getPrevious() == firstAfterTry) {
                        newTryBlock = new ExceptionBlock(tryBlock.getFirstInstruction(), lastBeforeHandler);
                    }
                }

                if (newTryBlock != null) {
                    final List<ExceptionHandler> siblings = findHandlers(tryBlock, handlers);

                    for (int j = 0; j < siblings.size(); j++) {
                        final ExceptionHandler sibling = siblings.get(j);
                        final int index = handlers.indexOf(sibling);

                        if (sibling.isCatch()) {
                            handlers.set(
                                index,
                                ExceptionHandler.createCatch(
                                    newTryBlock,
                                    sibling.getHandlerBlock(),
                                    sibling.getCatchType()
                                )
                            );
                        }
                        else {
                            handlers.set(
                                index,
                                ExceptionHandler.createFinally(
                                    newTryBlock,
                                    sibling.getHandlerBlock()
                                )
                            );
                        }
                    }
                }
            }
        }

        //
        // Look for finally blocks which duplicate an outer catch.
        //

        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);
            final ExceptionBlock tryBlock = handler.getTryBlock();
            final ExceptionBlock handlerBlock = handler.getHandlerBlock();

            if (!handler.isFinally()) {
                continue;
            }

            final ExceptionHandler innermostHandler = findInnermostExceptionHandler(
                tryBlock.getFirstInstruction().getOffset(),
                handler
            );

            if (innermostHandler == null ||
                innermostHandler == handler ||
                innermostHandler.isFinally()) {

                continue;
            }

            for (int j = 0; j < handlers.size(); j++) {
                final ExceptionHandler sibling = handlers.get(j);

                if (sibling != handler &&
                    sibling != innermostHandler &&
                    sibling.getTryBlock().equals(handlerBlock) &&
                    sibling.getHandlerBlock().equals(innermostHandler.getHandlerBlock())) {

                    handlers.remove(j);

                    if (j < i) {
                        --i;
                        break;
                    }

                    --j;
                }
            }
        }
    }

    private void alignFinallyBlocksWithSiblingCatchBlocks(final List<ExceptionHandler> handlers) {
    outer:
        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);

            if (handler.isCatch()) {
                continue;
            }

            final ExceptionBlock tryBlock = handler.getTryBlock();
            final ExceptionBlock handlerBlock = handler.getHandlerBlock();

            for (int j = 0; j < handlers.size(); j++) {
                if (i == j) {
                    continue;
                }

                final ExceptionHandler other = handlers.get(j);
                final ExceptionBlock otherTry = other.getTryBlock();
                final ExceptionBlock otherHandler = other.getHandlerBlock();

                if (other.isCatch() &&
                    otherHandler.getLastInstruction().getNext() == handlerBlock.getFirstInstruction() &&
                    otherTry.getFirstInstruction() == tryBlock.getFirstInstruction() &&
                    otherTry.getLastInstruction().getOffset() < tryBlock.getLastInstruction().getOffset() &&
                    tryBlock.getLastInstruction().getEndOffset() > otherHandler.getFirstInstruction().getOffset()) {

                    handlers.set(
                        i,
                        ExceptionHandler.createFinally(
                            new ExceptionBlock(
                                tryBlock.getFirstInstruction(),
                                otherHandler.getFirstInstruction().getPrevious()
                            ),
                            handlerBlock
                        )
                    );

                    --i;
                    continue outer;
                }
            }
        }
    }

    private void mergeSharedHandlers(final List<ExceptionHandler> handlers) {
        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);
            final List<ExceptionHandler> duplicates = findDuplicateHandlers(handler, handlers);

            for (int j = 0; j < duplicates.size() - 1; j++) {
                final ExceptionHandler h1 = duplicates.get(j);
                final ExceptionHandler h2 = duplicates.get(1 + j);

                final ExceptionBlock try1 = h1.getTryBlock();
                final ExceptionBlock try2 = h2.getTryBlock();

                final Instruction head = try1.getLastInstruction().getNext();

                Instruction tail = try2.getFirstInstruction().getPrevious();

                final int i1 = handlers.indexOf(h1);
                final int i2 = handlers.indexOf(h2);

                if (head != tail) {
                    if (tail.getOpCode().isUnconditionalBranch()) {
                        switch (tail.getOpCode()) {
                            case GOTO:
                            case GOTO_W:
                            case RETURN:
                                tail = tail.getPrevious();
                                break;

                            case IRETURN:
                            case LRETURN:
                            case FRETURN:
                            case DRETURN:
                            case ARETURN:
                                tail = tail.getPrevious().getPrevious();
                                break;
                        }
                    }

//                    if (!areAllRemoved(head, tail)) {
//                        continue;
//                    }

                    if (h1.isCatch()) {
                        handlers.set(
                            i1,
                            ExceptionHandler.createCatch(
                                new ExceptionBlock(try1.getFirstInstruction(), try2.getLastInstruction()),
                                h1.getHandlerBlock(),
                                h1.getCatchType()
                            )
                        );
                    }
                    else {
                        handlers.set(
                            i1,
                            ExceptionHandler.createFinally(
                                new ExceptionBlock(try1.getFirstInstruction(), try2.getLastInstruction()),
                                h1.getHandlerBlock()
                            )
                        );
                    }

                    duplicates.set(j, handlers.get(i1));
                    duplicates.remove(j + 1);
                    handlers.remove(i2);

                    if (i2 <= i) {
                        --i;
                    }

                    --j;
                }
            }
        }
    }

    private void trimAggressiveCatchBlocks() {
        final List<ExceptionHandler> handlers = _exceptionHandlers;

    outer:
        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);
            final ExceptionBlock tryBlock = handler.getTryBlock();
            final ExceptionBlock handlerBlock = handler.getHandlerBlock();

            if (!handler.isCatch()) {
                continue;
            }

            for (int j = 0; j < handlers.size(); j++) {
                if (i == j) {
                    continue;
                }

                final ExceptionHandler other = handlers.get(j);

                if (!other.isFinally()) {
                    continue;
                }

                final ExceptionBlock otherTry = other.getTryBlock();
                final ExceptionBlock otherHandler = other.getHandlerBlock();

                if (handlerBlock.getFirstInstruction().getOffset() < otherHandler.getFirstInstruction().getOffset() &&
                    handlerBlock.intersects(otherHandler) &&
                    !otherTry.contains(tryBlock)) {

                    handlers.set(
                        i--,
                        ExceptionHandler.createCatch(
                            tryBlock,
                            new ExceptionBlock(
                                handlerBlock.getFirstInstruction(),
                                otherHandler.getFirstInstruction().getPrevious()
                            ),
                            handler.getCatchType()
                        )
                    );

                    continue outer;
                }
            }
        }
    }

    private void removeSelfHandlingFinallyHandlers() {
        final List<ExceptionHandler> handlers = _exceptionHandlers;

        //
        // Remove self-handling finally blocks.
        //

        for (int i = 0; i < handlers.size(); i++) {
            final ExceptionHandler handler = handlers.get(i);
            final ExceptionBlock tryBlock = handler.getTryBlock();
            final ExceptionBlock handlerBlock = handler.getHandlerBlock();

            if (handler.isFinally() &&
                handlerBlock.getFirstInstruction() == tryBlock.getFirstInstruction() &&
                tryBlock.getLastInstruction().getOffset() < handlerBlock.getLastInstruction().getEndOffset()) {

                handlers.remove(i--);
                continue;
            }
        }
    }

    private ExceptionHandler findInnermostExceptionHandler(final int offsetInTryBlock, final ExceptionHandler exclude) {
        ExceptionHandler result = null;

        for (final ExceptionHandler handler : _exceptionHandlers) {
            if (handler == exclude) {
                continue;
            }

            final ExceptionBlock tryBlock = handler.getTryBlock();

            if (tryBlock.getFirstInstruction().getOffset() <= offsetInTryBlock &&
                offsetInTryBlock < tryBlock.getLastInstruction().getEndOffset() &&
                (result == null ||
                 tryBlock.getFirstInstruction().getOffset() > result.getTryBlock().getFirstInstruction().getOffset())) {

                result = handler;
            }
        }

        return result;
    }

    private void closeTryHandlerGaps() {
        //
        // Java does this retarded thing where a try block gets split along exit branches,
        // but with the split parts sharing the same handler.  We can't represent this in
        // out AST, so just merge the parts back together.
        //

        final List<ExceptionHandler> handlers = _exceptionHandlers;

        for (int i = 0; i < handlers.size() - 1; i++) {
            final ExceptionHandler current = handlers.get(i);
            final ExceptionHandler next = handlers.get(i + 1);

            if (current.getHandlerBlock().getFirstInstruction() == next.getHandlerBlock().getFirstInstruction() &&
                current.getHandlerBlock().getLastInstruction() == next.getHandlerBlock().getLastInstruction()) {

                final Instruction lastInCurrent = current.getTryBlock().getLastInstruction();
                final Instruction firstInNext = next.getTryBlock().getFirstInstruction();
                final Instruction branchInBetween = firstInNext.getPrevious();

                final Instruction beforeBranch;

                if (branchInBetween != null) {
                    beforeBranch = branchInBetween.getPrevious();
                }
                else {
                    beforeBranch = null;
                }

                if (branchInBetween != null &&
                    branchInBetween.getOpCode().isBranch() &&
                    lastInCurrent == beforeBranch) {

                    final ExceptionHandler newHandler;

                    if (current.isFinally()) {
                        newHandler = ExceptionHandler.createFinally(
                            new ExceptionBlock(
                                current.getTryBlock().getFirstInstruction(),
                                next.getTryBlock().getLastInstruction()
                            ),
                            new ExceptionBlock(
                                current.getHandlerBlock().getFirstInstruction(),
                                current.getHandlerBlock().getLastInstruction()
                            )
                        );
                    }
                    else {
                        newHandler = ExceptionHandler.createCatch(
                            new ExceptionBlock(
                                current.getTryBlock().getFirstInstruction(),
                                next.getTryBlock().getLastInstruction()
                            ),
                            new ExceptionBlock(
                                current.getHandlerBlock().getFirstInstruction(),
                                current.getHandlerBlock().getLastInstruction()
                            ),
                            current.getCatchType()
                        );
                    }

                    handlers.set(i, newHandler);
                    handlers.remove(i + 1);
                    --i;
                }
            }
        }
    }

    private static ExceptionHandler findFirstHandler(final ExceptionBlock tryBlock, final Collection<ExceptionHandler> handlers) {
        ExceptionHandler result = null;

        for (final ExceptionHandler handler : handlers) {
            if (handler.getTryBlock().equals(tryBlock) &&
                (result == null ||
                 handler.getHandlerBlock().getFirstInstruction().getOffset() < result.getHandlerBlock().getFirstInstruction().getOffset())) {

                result = handler;
            }
        }

        return result;
    }

    private static List<ExceptionHandler> findHandlers(final ExceptionBlock tryBlock, final Collection<ExceptionHandler> handlers) {
        List<ExceptionHandler> result = null;

        for (final ExceptionHandler handler : handlers) {
            if (handler.getTryBlock().equals(tryBlock)) {
                if (result == null) {
                    result = new ArrayList<>();
                }

                result.add(handler);
            }
        }

        if (result == null) {
            return Collections.emptyList();
        }

        Collections.sort(
            result,
            new Comparator<ExceptionHandler>() {
                @Override
                public int compare(@NotNull final ExceptionHandler o1, @NotNull final ExceptionHandler o2) {
                    return Integer.compare(
                        o1.getHandlerBlock().getFirstInstruction().getOffset(),
                        o2.getHandlerBlock().getFirstInstruction().getOffset()
                    );
                }
            }
        );

        return result;
    }

    private static List<ExceptionHandler> findDuplicateHandlers(final ExceptionHandler handler, final Collection<ExceptionHandler> handlers) {
        final List<ExceptionHandler> result = new ArrayList<>();

        for (final ExceptionHandler other : handlers) {
            if (other.getHandlerBlock().equals(handler.getHandlerBlock())) {
                if (handler.isFinally()) {
                    if (other.isFinally()) {
                        result.add(other);
                    }
                }
                else if (other.isCatch() &&
                         MetadataHelper.isSameType(other.getCatchType(), handler.getCatchType())) {
                    result.add(other);
                }
            }
        }

        Collections.sort(
            result,
            new Comparator<ExceptionHandler>() {
                @Override
                public int compare(@NotNull final ExceptionHandler o1, @NotNull final ExceptionHandler o2) {
                    return Integer.compare(
                        o1.getTryBlock().getFirstInstruction().getOffset(),
                        o2.getTryBlock().getFirstInstruction().getOffset()
                    );
                }
            }
        );

        return result;
    }

    @SuppressWarnings("ConstantConditions")
    private List<ByteCode> performStackAnalysis() {
        final Map<Instruction, ByteCode> byteCodeMap = new LinkedHashMap<>();
        final Map<Instruction, ControlFlowNode> nodeMap = new IdentityHashMap<>();
        final InstructionCollection instructions = _instructions;
        final List<ExceptionHandler> exceptionHandlers = new ArrayList<>();

        for (final ControlFlowNode node : _cfg.getNodes()) {
            if (node.getExceptionHandler() != null) {
                exceptionHandlers.add(node.getExceptionHandler());
            }

            if (node.getNodeType() != ControlFlowNodeType.Normal) {
                continue;
            }

            for (Instruction p = node.getStart();
                 p != null && p.getOffset() < node.getEnd().getEndOffset();
                 p = p.getNext()) {

                nodeMap.put(p, node);
            }
        }

        _exceptionHandlers.retainAll(exceptionHandlers);

        final List<ByteCode> body = new ArrayList<>(instructions.size());
        final StackMappingVisitor stackMapper = new StackMappingVisitor();
        final InstructionVisitor instructionVisitor = stackMapper.visitBody(_body);
        final StrongBox<AstCode> codeBox = new StrongBox<>();
        final StrongBox<Object> operandBox = new StrongBox<>();

        _factory = CoreMetadataFactory.make(_context.getCurrentType(), _context.getCurrentMethod());

        for (final Instruction instruction : instructions) {
            final OpCode opCode = instruction.getOpCode();

            AstCode code = CODES[opCode.ordinal()];
            Object operand = instruction.hasOperand() ? instruction.getOperand(0) : null;

            final Object secondOperand = instruction.getOperandCount() > 1 ? instruction.getOperand(1) : null;

            codeBox.set(code);
            operandBox.set(operand);

            final int offset = _originalInstructionMap.get(instruction).getOffset();

            if (AstCode.expandMacro(codeBox, operandBox, _body, offset)) {
                code = codeBox.get();
                operand = operandBox.get();
            }

            final ByteCode byteCode = new ByteCode();

            byteCode.instruction = instruction;
            byteCode.offset = instruction.getOffset();
            byteCode.endOffset = instruction.getEndOffset();
            byteCode.code = code;
            byteCode.operand = operand;
            byteCode.secondOperand = secondOperand;
            byteCode.popCount = InstructionHelper.getPopDelta(instruction, _body);
            byteCode.pushCount = InstructionHelper.getPushDelta(instruction, _body);

            byteCodeMap.put(instruction, byteCode);
            body.add(byteCode);
        }

        for (int i = 0, n = body.size() - 1; i < n; i++) {
            final ByteCode next = body.get(i + 1);
            final ByteCode current = body.get(i);

            current.next = next;
            next.previous = current;
        }

        final Stack<ByteCode> agenda = new Stack<>();
        final int variableCount = _body.getMaxLocals();
        final VariableSlot[] unknownVariables = VariableSlot.makeUnknownState(variableCount);
        final MethodReference method = _body.getMethod();
        final List<ParameterDefinition> parameters = method.getParameters();
        final boolean hasThis = _body.hasThis();

        if (hasThis) {
            if (method.isConstructor()) {
                unknownVariables[0] = new VariableSlot(FrameValue.UNINITIALIZED_THIS, EMPTY_DEFINITIONS);
            }
            else {
                unknownVariables[0] = new VariableSlot(FrameValue.makeReference(_context.getCurrentType()), EMPTY_DEFINITIONS);
            }
        }

        for (int i = 0; i < parameters.size(); i++) {
            final ParameterDefinition parameter = parameters.get(i);
            final TypeReference parameterType = parameter.getParameterType();
            final int slot = parameter.getSlot();

            switch (parameterType.getSimpleType()) {
                case Boolean:
                case Byte:
                case Character:
                case Short:
                case Integer:
                    unknownVariables[slot] = new VariableSlot(FrameValue.INTEGER, EMPTY_DEFINITIONS);
                    break;
                case Long:
                    unknownVariables[slot] = new VariableSlot(FrameValue.LONG, EMPTY_DEFINITIONS);
                    unknownVariables[slot + 1] = new VariableSlot(FrameValue.TOP, EMPTY_DEFINITIONS);
                    break;
                case Float:
                    unknownVariables[slot] = new VariableSlot(FrameValue.FLOAT, EMPTY_DEFINITIONS);
                    break;
                case Double:
                    unknownVariables[slot] = new VariableSlot(FrameValue.DOUBLE, EMPTY_DEFINITIONS);
                    unknownVariables[slot + 1] = new VariableSlot(FrameValue.TOP, EMPTY_DEFINITIONS);
                    break;
                default:
                    unknownVariables[slot] = new VariableSlot(FrameValue.makeReference(parameterType), EMPTY_DEFINITIONS);
                    break;
            }
        }

        for (final ExceptionHandler handler : exceptionHandlers) {
            final ByteCode handlerStart = byteCodeMap.get(handler.getHandlerBlock().getFirstInstruction());

            handlerStart.stackBefore = EMPTY_STACK;
            handlerStart.variablesBefore = unknownVariables;

            final ByteCode loadException = new ByteCode();
            final TypeReference catchType;

            if (handler.isFinally()) {
                catchType = _factory.makeNamedType("java.lang.Throwable");
            }
            else {
                catchType = handler.getCatchType();
            }

            loadException.code = AstCode.LoadException;
            loadException.operand = catchType;
            loadException.popCount = 0;
            loadException.pushCount = 1;

            _loadExceptions.put(handler, loadException);

            handlerStart.stackBefore = new StackSlot[] {
                new StackSlot(
                    FrameValue.makeReference(catchType),
                    new ByteCode[] { loadException }
                )
            };

            agenda.push(handlerStart);
        }

        body.get(0).stackBefore = EMPTY_STACK;
        body.get(0).variablesBefore = unknownVariables;

        agenda.push(body.get(0));

        //
        // Process agenda.
        //
        while (!agenda.isEmpty()) {
            final ByteCode byteCode = agenda.pop();

            //
            // Calculate new stack.
            //

            stackMapper.visitFrame(byteCode.getFrameBefore());
            instructionVisitor.visit(byteCode.instruction);

            final StackSlot[] newStack = createModifiedStack(byteCode, stackMapper);

            //
            // Calculate new variable state.
            //

            final VariableSlot[] newVariableState = VariableSlot.cloneVariableState(byteCode.variablesBefore);
            final Map<Instruction, TypeReference> initializations = stackMapper.getInitializations();

            for (int i = 0; i < newVariableState.length; i++) {
                final VariableSlot slot = newVariableState[i];

                if (slot.isUninitialized()) {
                    final Object parameter = slot.value.getParameter();

                    if (parameter instanceof Instruction) {
                        final Instruction instruction = (Instruction) parameter;
                        final TypeReference initializedType = initializations.get(instruction);

                        if (initializedType != null) {
                            newVariableState[i] = new VariableSlot(
                                FrameValue.makeReference(initializedType),
                                slot.definitions
                            );
                        }
                    }
                }
            }

            if (byteCode.isVariableDefinition()) {
                final int slot = ((VariableReference) byteCode.operand).getSlot();

                newVariableState[slot] = new VariableSlot(
                    stackMapper.getLocalValue(slot),
                    new ByteCode[] { byteCode }
                );
            }

            //
            // Find all successors.
            //
            final ArrayList<ByteCode> branchTargets = new ArrayList<>();
            final ControlFlowNode node = nodeMap.get(byteCode.instruction);

            if (byteCode.instruction != node.getEnd()) {
                branchTargets.add(byteCode.next);
            }
            else {
                for (final ControlFlowNode successor : node.getSuccessors()) {
                    if (successor.getNodeType() != ControlFlowNodeType.Normal) {
                        continue;
                    }

                    final Instruction targetInstruction = successor.getStart();
                    final ByteCode target = byteCodeMap.get(targetInstruction);

                    if (target.label == null) {
                        target.label = new Label();
                        target.label.setName(target.makeLabelName());
                    }

                    branchTargets.add(target);
                }
            }

            //
            // Apply the state to successors.
            //
            for (final ByteCode branchTarget : branchTargets) {
                if (branchTarget.stackBefore == null && branchTarget.variablesBefore == null) {
                    if (branchTargets.size() == 1) {
                        branchTarget.stackBefore = newStack;
                        branchTarget.variablesBefore = newVariableState;
                    }
                    else {
                        //
                        // Do not share data for several bytecodes.
                        //
                        branchTarget.stackBefore = StackSlot.modifyStack(newStack, 0, null);
                        branchTarget.variablesBefore = VariableSlot.cloneVariableState(newVariableState);
                    }

                    agenda.push(branchTarget);
                }
                else {
                    if (branchTarget.stackBefore.length != newStack.length) {
                        throw new IllegalStateException(
                            "Inconsistent stack size at " + branchTarget.name()
                            + " (coming from " + byteCode.name() + ")."
                        );
                    }

                    //
                    // Be careful not to change our new data; it might be reused for several branch targets.
                    // In general, be careful that two bytecodes never share data structures.
                    //

                    boolean modified = false;

                    //
                    // Merge stacks; modify the target.
                    //
                    for (int i = 0; i < newStack.length; i++) {
                        final ByteCode[] oldDefinitions = branchTarget.stackBefore[i].definitions;
                        final ByteCode[] newDefinitions = ArrayUtilities.union(oldDefinitions, newStack[i].definitions);

                        if (newDefinitions.length > oldDefinitions.length) {
                            branchTarget.stackBefore[i] = new StackSlot(newStack[i].value, newDefinitions);
                            modified = true;
                        }
                    }

                    //
                    // Merge variables; modify the target;
                    //
                    for (int i = 0; i < newVariableState.length; i++) {
                        final VariableSlot oldSlot = branchTarget.variablesBefore[i];
                        final VariableSlot newSlot = newVariableState[i];

                        if (!oldSlot.isUninitialized()) {
                            if (newSlot.isUninitialized()) {
                                branchTarget.variablesBefore[i] = newSlot;
                                modified = true;
                            }
                            else {
                                final ByteCode[] oldDefinitions = oldSlot.definitions;
                                final ByteCode[] newDefinitions = ArrayUtilities.union(oldSlot.definitions, newSlot.definitions);

                                if (newDefinitions.length > oldDefinitions.length) {
                                    branchTarget.variablesBefore[i] = new VariableSlot(oldSlot.value, newDefinitions);
                                    modified = true;
                                }
                            }
                        }
                    }

                    if (modified) {
                        agenda.push(branchTarget);
                    }
                }
            }
        }

        //
        // Occasionally, compilers or obfuscators may generate unreachable code (which might be intentionally invalid).
        // It should be safe to simply remove it.
        //

        ArrayList<ByteCode> unreachable = null;

        for (final ByteCode byteCode : body) {
            if (byteCode.stackBefore == null) {
                if (unreachable == null) {
                    unreachable = new ArrayList<>();
                }

                unreachable.add(byteCode);
            }
        }

        if (unreachable != null) {
            body.removeAll(unreachable);
        }

        //
        // Generate temporary variables to replace stack values.
        //
        for (final ByteCode byteCode : body) {
            final int popCount = byteCode.popCount != -1 ? byteCode.popCount : byteCode.stackBefore.length;

            int argumentIndex = 0;

            for (int i = byteCode.stackBefore.length - popCount; i < byteCode.stackBefore.length; i++) {
                final Variable tempVariable = new Variable();

                tempVariable.setName(format("stack_%1$02X_%2$d", byteCode.offset, argumentIndex));
                tempVariable.setGenerated(true);

                final FrameValue value = byteCode.stackBefore[i].value;

                switch (value.getType()) {
                    case Integer:
                        tempVariable.setType(BuiltinTypes.Integer);
                        break;
                    case Float:
                        tempVariable.setType(BuiltinTypes.Float);
                        break;
                    case Long:
                        tempVariable.setType(BuiltinTypes.Long);
                        break;
                    case Double:
                        tempVariable.setType(BuiltinTypes.Double);
                        break;
                    case UninitializedThis:
                        tempVariable.setType(_context.getCurrentType());
                        break;
                    case Reference:
                        TypeReference refType = (TypeReference) value.getParameter();
                        if (refType.isWildcardType()) {
                            refType = refType.hasSuperBound() ? refType.getSuperBound() : refType.getExtendsBound();
                        }
                        tempVariable.setType(refType);
                        break;
                }

                byteCode.stackBefore[i] = new StackSlot(value, byteCode.stackBefore[i].definitions, tempVariable);

                for (final ByteCode pushedBy : byteCode.stackBefore[i].definitions) {
                    if (pushedBy.storeTo == null) {
                        pushedBy.storeTo = new ArrayList<>();
                    }

                    pushedBy.storeTo.add(tempVariable);
                }

                argumentIndex++;
            }
        }

        //
        // Try to use a single temporary variable instead of several, if possible (especially useful for DUP).
        // This has to be done after all temporary variables are assigned so we know about all loads.
        //
        for (final ByteCode byteCode : body) {
            if (byteCode.storeTo != null && byteCode.storeTo.size() > 1) {
                final List<Variable> localVariables = byteCode.storeTo;

                //
                // For each of the variables, find the location where it is loaded; there should be exactly one.
                //
                List<StackSlot> loadedBy = null;

                for (final Variable local : localVariables) {
                inner:
                    for (final ByteCode bc : body) {
                        for (final StackSlot s : bc.stackBefore) {
                            if (s.loadFrom == local) {
                                if (loadedBy == null) {
                                    loadedBy = new ArrayList<>();
                                }

                                loadedBy.add(s);
                                break inner;
                            }
                        }
                    }
                }

                if (loadedBy == null) {
                    continue;
                }

                //
                // We know that all the temp variables have a single load; now make sure they have a single store.
                //
                boolean singleStore = true;
                TypeReference type = null;

                for (final StackSlot slot : loadedBy) {
                    if (slot.definitions.length != 1) {
                        singleStore = false;
                        break;
                    }
                    else if (slot.definitions[0] != byteCode) {
                        singleStore = false;
                        break;
                    }
                    else if (type == null) {
                        switch (slot.value.getType()) {
                            case Integer:
                                type = BuiltinTypes.Integer;
                                break;
                            case Float:
                                type = BuiltinTypes.Float;
                                break;
                            case Long:
                                type = BuiltinTypes.Long;
                                break;
                            case Double:
                                type = BuiltinTypes.Double;
                                break;
                            case Reference:
                                type = (TypeReference) slot.value.getParameter();
                                if (type.isWildcardType()) {
                                    type = type.hasSuperBound() ? type.getSuperBound() : type.getExtendsBound();
                                }
                                break;
                        }
                    }
                }

                if (!singleStore) {
                    continue;
                }

                //
                // We can now reduce everything into a single variable.
                //
                final Variable tempVariable = new Variable();

                tempVariable.setName(format("expr_%1$02X", byteCode.offset));
                tempVariable.setGenerated(true);
                tempVariable.setType(type);

                byteCode.storeTo = Collections.singletonList(tempVariable);

                for (final ByteCode bc : body) {
                    for (int i = 0; i < bc.stackBefore.length; i++) {
                        //
                        // Is it one of the variables we merged?
                        //
                        if (localVariables.contains(bc.stackBefore[i].loadFrom)) {
                            //
                            // Replace with the new temp variable.
                            //
                            bc.stackBefore[i] = new StackSlot(bc.stackBefore[i].value, bc.stackBefore[i].definitions, tempVariable);
                        }
                    }
                }
            }
        }

        //
        // Split and convert the normal local variables.
        //
        convertLocalVariables(body);

        //
        // Convert branch targets to labels.
        //
        for (final ByteCode byteCode : body) {
            if (byteCode.operand instanceof Instruction[]) {
                final Instruction[] branchTargets = (Instruction[]) byteCode.operand;
                final Label[] newOperand = new Label[branchTargets.length];

                for (int i = 0; i < branchTargets.length; i++) {
                    newOperand[i] = byteCodeMap.get(branchTargets[i]).label;
                }

                byteCode.operand = newOperand;
            }
            else if (byteCode.operand instanceof Instruction) {
                //noinspection SuspiciousMethodCalls
                byteCode.operand = byteCodeMap.get(byteCode.operand).label;
            }
            else if (byteCode.operand instanceof SwitchInfo) {
                final SwitchInfo switchInfo = (SwitchInfo) byteCode.operand;
                final Instruction[] branchTargets = ArrayUtilities.prepend(switchInfo.getTargets(), switchInfo.getDefaultTarget());
                final Label[] newOperand = new Label[branchTargets.length];

                for (int i = 0; i < branchTargets.length; i++) {
                    newOperand[i] = byteCodeMap.get(branchTargets[i]).label;
                }

                byteCode.operand = newOperand;
            }
        }

        //
        // Convert parameters to Variables.
        //
//        convertParameters(body);

        return body; //removeRedundantFinallyBlocks(body, byteCodeMap, exceptionHandlers);
    }

    private static StackSlot[] createModifiedStack(final ByteCode byteCode, final StackMappingVisitor stackMapper) {
        final Map<Instruction, TypeReference> initializations = stackMapper.getInitializations();
        final StackSlot[] oldStack = byteCode.stackBefore.clone();

        for (int i = 0; i < oldStack.length; i++) {
            if (oldStack[i].value.getParameter() instanceof Instruction) {
                final TypeReference initializedType = initializations.get(oldStack[i].value.getParameter());

                if (initializedType != null) {
                    oldStack[i] = new StackSlot(
                        FrameValue.makeReference(initializedType),
                        oldStack[i].definitions,
                        oldStack[i].loadFrom
                    );
                }
            }
        }

        if (byteCode.popCount == 0 && byteCode.pushCount == 0) {
            return oldStack;
        }

        switch (byteCode.code) {
            case Dup:
                return ArrayUtilities.append(
                    oldStack,
                    new StackSlot(stackMapper.getStackValue(0), oldStack[oldStack.length - 1].definitions)
                );

            case DupX1:
                return ArrayUtilities.insert(
                    oldStack,
                    oldStack.length - 2,
                    new StackSlot(stackMapper.getStackValue(0), oldStack[oldStack.length - 1].definitions)
                );

            case DupX2:
                return ArrayUtilities.insert(
                    oldStack,
                    oldStack.length - 3,
                    new StackSlot(stackMapper.getStackValue(0), oldStack[oldStack.length - 1].definitions)
                );

            case Dup2:
                return ArrayUtilities.append(
                    oldStack,
                    new StackSlot(stackMapper.getStackValue(1), oldStack[oldStack.length - 2].definitions),
                    new StackSlot(stackMapper.getStackValue(0), oldStack[oldStack.length - 1].definitions)
                );

            case Dup2X1:
                return ArrayUtilities.insert(
                    oldStack,
                    oldStack.length - 3,
                    new StackSlot(stackMapper.getStackValue(1), oldStack[oldStack.length - 2].definitions),
                    new StackSlot(stackMapper.getStackValue(0), oldStack[oldStack.length - 1].definitions)
                );

            case Dup2X2:
                return ArrayUtilities.insert(
                    oldStack,
                    oldStack.length - 4,
                    new StackSlot(stackMapper.getStackValue(1), oldStack[oldStack.length - 2].definitions),
                    new StackSlot(stackMapper.getStackValue(0), oldStack[oldStack.length - 1].definitions)
                );

            case Swap:
                final StackSlot[] newStack = new StackSlot[oldStack.length];

                ArrayUtilities.copy(oldStack, newStack);

                final StackSlot temp = newStack[oldStack.length - 1];

                newStack[oldStack.length - 1] = newStack[oldStack.length - 2];
                newStack[oldStack.length - 2] = temp;

                return newStack;

            default:
                final FrameValue[] pushValues = new FrameValue[byteCode.pushCount];

                for (int i = 0; i < byteCode.pushCount; i++) {
                    pushValues[pushValues.length - i - 1] = stackMapper.getStackValue(i);
                }

                return StackSlot.modifyStack(
                    oldStack,
                    byteCode.popCount != -1 ? byteCode.popCount : oldStack.length,
                    byteCode,
                    pushValues
                );
        }
    }

    private final static class VariableInfo {
        final Variable variable;
        final List<ByteCode> definitions;
        final List<ByteCode> references;

        VariableInfo(final Variable variable, final List<ByteCode> definitions, final List<ByteCode> references) {
            this.variable = variable;
            this.definitions = definitions;
            this.references = references;
        }
    }

    @SuppressWarnings("ConstantConditions")
    private void convertLocalVariables(final List<ByteCode> body) {
        final MethodDefinition method = _context.getCurrentMethod();
        final List<ParameterDefinition> parameters = method.getParameters();
        final VariableDefinitionCollection variables = _body.getVariables();
        final ParameterDefinition[] parameterMap = new ParameterDefinition[_body.getMaxLocals()];
        final boolean hasThis = _body.hasThis();

        if (hasThis) {
            parameterMap[0] = _body.getThisParameter();
        }

        for (final ParameterDefinition parameter : parameters) {
            parameterMap[parameter.getSlot()] = parameter;
        }

//        for (final VariableDefinition variableDefinition : variables) {
        for (int slot = 0; slot < _body.getMaxLocals(); slot++) {
            //
            // Find all definitions of and references to this variable.
            //

            final List<ByteCode> definitions = new ArrayList<>();
            final List<ByteCode> references = new ArrayList<>();

            for (final ByteCode b : body) {
                if (b.operand instanceof VariableReference &&
                    ((VariableReference) b.operand).getSlot() == slot) {

                    if (b.isVariableDefinition()) {
                        definitions.add(b);
                    }
                    else {
                        references.add(b);
                    }
                }
            }

            final List<VariableInfo> newVariables;
            boolean fromUnknownDefinition = false;

            if (_optimize) {
                for (final ByteCode b : references) {
                    if (b.variablesBefore[slot].isUninitialized()) {
                        fromUnknownDefinition = true;
                        break;
                    }
                }
            }

            final ParameterDefinition parameter = parameterMap[slot];

            if (parameter != null) {
                final Variable variable = new Variable();

                variable.setName(
                    StringUtilities.isNullOrEmpty(parameter.getName()) ? "p" + parameter.getPosition()
                                                                       : parameter.getName()
                );

                variable.setType(parameter.getParameterType());
                variable.setOriginalParameter(parameter);

                final VariableInfo variableInfo = new VariableInfo(variable, definitions, references);

                newVariables = Collections.singletonList(variableInfo);
            }
            else if (!_optimize || fromUnknownDefinition) {
                final VariableDefinition definition = null; //findDefinition(slot, definitions, variables);
                final Variable variable = new Variable();

                if (definition != null) {
                    variable.setType(definition.getVariableType());

                    variable.setName(
                        StringUtilities.isNullOrEmpty(definition.getName()) ? "var_" + slot
                                                                            : definition.getName()
                    );
                }
                else {
                    variable.setName("var_" + slot);

                    for (final ByteCode b : definitions) {
                        final FrameValue stackValue = b.stackBefore[b.stackBefore.length - b.popCount].value;

                        if (stackValue != FrameValue.NULL &&
                            stackValue != FrameValue.UNINITIALIZED &&
                            stackValue != FrameValue.UNINITIALIZED_THIS) {

                            final TypeReference variableType;

                            switch (stackValue.getType()) {
                                case Integer:
                                    variableType = BuiltinTypes.Integer;
                                    break;
                                case Float:
                                    variableType = BuiltinTypes.Float;
                                    break;
                                case Long:
                                    variableType = BuiltinTypes.Long;
                                    break;
                                case Double:
                                    variableType = BuiltinTypes.Double;
                                    break;
                                case Uninitialized:
                                    if (stackValue.getParameter() instanceof Instruction &&
                                        ((Instruction) stackValue.getParameter()).getOpCode() == OpCode.NEW) {

                                        variableType = ((Instruction) stackValue.getParameter()).getOperand(0);
                                    }
                                    else if (definition != null) {
                                        variableType = definition.getVariableType();
                                    }
                                    else {
                                        variableType = BuiltinTypes.Object;
                                    }
                                    break;
                                case UninitializedThis:
                                    variableType = _context.getCurrentType();
                                    break;
                                case Reference:
                                    variableType = (TypeReference) stackValue.getParameter();
                                    break;
                                case Address:
                                    variableType = BuiltinTypes.Integer;
                                    break;
                                default:
                                    if (definition != null) {
                                        variableType = definition.getVariableType();
                                    }
                                    else {
                                        variableType = BuiltinTypes.Object;
                                    }
                                    break;
                            }

                            variable.setType(variableType);
                            break;
                        }
                    }

                    if (variable.getType() == null) {
                        variable.setType(BuiltinTypes.Object);
                    }
                }

                if (definition == null) {
                    variable.setOriginalVariable(new VariableDefinition(slot, variable.getName(), method, variable.getType()));
                }
                else {
                    variable.setOriginalVariable(definition);
                }

                variable.setGenerated(false);

                final VariableInfo variableInfo = new VariableInfo(variable, definitions, references);

                newVariables = Collections.singletonList(variableInfo);
            }
            else {
                newVariables = new ArrayList<>();

                for (final ByteCode b : definitions) {
                    final Variable variable = new Variable();
                    final VariableDefinition definition = findDefinition(slot, Collections.singletonList(b), variables);

                    if (definition != null && !StringUtilities.isNullOrEmpty(definition.getName())) {
                        variable.setName(definition.getName());
                    }
                    else {
                        variable.setName(format("var_%1$d_%2$02X", slot, b.offset));
                    }

                    final TypeReference variableType;
                    final FrameValue stackValue;

                    if (b.code == AstCode.Inc) {
                        stackValue = FrameValue.INTEGER;
                    }
                    else {
                        stackValue = b.stackBefore[b.stackBefore.length - b.popCount].value;
                    }

                    if (definition != null && definition.isFromMetadata()) {
                        variable.setType(definition.getVariableType());
                    }
                    else {
                        switch (stackValue.getType()) {
                            case Integer:
                                variableType = BuiltinTypes.Integer;
                                break;
                            case Float:
                                variableType = BuiltinTypes.Float;
                                break;
                            case Long:
                                variableType = BuiltinTypes.Long;
                                break;
                            case Double:
                                variableType = BuiltinTypes.Double;
                                break;
                            case UninitializedThis:
                                variableType = _context.getCurrentType();
                                break;
                            case Reference:
                                variableType = (TypeReference) stackValue.getParameter();
                                break;
                            case Address:
                                variableType = BuiltinTypes.Integer;
                                break;
                            default:
                                if (definition != null) {
                                    variableType = definition.getVariableType();
                                }
                                else {
                                    variableType = BuiltinTypes.Object;
                                }
                                break;
                        }

                        variable.setType(variableType);
                    }

                    if (definition == null) {
                        variable.setOriginalVariable(new VariableDefinition(slot, variable.getName(), method, variable.getType()));
                    }
                    else {
                        variable.setOriginalVariable(definition);
                    }

                    variable.setGenerated(false);

                    final VariableInfo variableInfo = new VariableInfo(
                        variable,
                        new ArrayList<ByteCode>(),
                        new ArrayList<ByteCode>()
                    );

                    variableInfo.definitions.add(b);
                    newVariables.add(variableInfo);
                }

                //
                // Add loads to the data structure; merge variables if necessary.
                //
                for (final ByteCode ref : references) {
                    final ByteCode[] refDefinitions = ref.variablesBefore[slot].definitions;

                    if (refDefinitions.length == 1) {
                        VariableInfo newVariable = null;

                        for (final VariableInfo v : newVariables) {
                            if (v.definitions.contains(refDefinitions[0])) {
                                newVariable = v;
                                break;
                            }
                        }

                        assert newVariable != null;

                        newVariable.references.add(ref);
                    }
                    else {
                        final ArrayList<VariableInfo> mergeVariables = new ArrayList<>();

                        for (final VariableInfo v : newVariables) {
                            boolean hasIntersection = false;

                        outer:
                            for (final ByteCode b1 : v.definitions) {
                                for (final ByteCode b2 : refDefinitions) {
                                    if (b1 == b2) {
                                        hasIntersection = true;
                                        break outer;
                                    }
                                }
                            }

                            if (hasIntersection) {
                                mergeVariables.add(v);
                            }
                        }

                        final ArrayList<ByteCode> mergedDefinitions = new ArrayList<>();
                        final ArrayList<ByteCode> mergedReferences = new ArrayList<>();

                        for (final VariableInfo v : mergeVariables) {
                            mergedDefinitions.addAll(v.definitions);
                            mergedReferences.addAll(v.references);
                        }

                        final VariableInfo mergedVariable = new VariableInfo(
                            mergeVariables.get(0).variable,
                            mergedDefinitions,
                            mergedReferences
                        );

                        mergedVariable.references.add(ref);
                        newVariables.removeAll(mergeVariables);
                        newVariables.add(mergedVariable);
                    }
                }
            }

            //
            // Set bytecode operands.
            //
            for (final VariableInfo newVariable : newVariables) {
                for (final ByteCode definition : newVariable.definitions) {
                    definition.operand = newVariable.variable;
                }
                for (final ByteCode reference : newVariable.references) {
                    reference.operand = newVariable.variable;
                }
            }
        }
    }

    private static VariableDefinition findDefinition(final int slot, final List<ByteCode> definitions, final VariableDefinitionCollection variables) {
        for (final ByteCode byteCode : definitions) {
            final int effectiveOffset;
            final OpCode op = byteCode.instruction.getOpCode();

            if (op.isStore()) {
                effectiveOffset = byteCode.offset + op.getSize() + op.getOperandType().getBaseSize();
            }
            else {
                effectiveOffset = byteCode.offset;
            }

            final VariableDefinition definition = variables.tryFind(slot, effectiveOffset);

            if (definition != null) {
                return definition;
            }
        }

        return null;
    }

    @SuppressWarnings("ConstantConditions")
    private List<Node> convertToAst(
        final List<ByteCode> body,
        final Set<ExceptionHandler> exceptionHandlers,
        final int startIndex,
        final MutableInteger endIndex) {

        final ArrayList<Node> ast = new ArrayList<>();

        int headStartIndex = startIndex;
        int tailStartIndex = startIndex;

        final MutableInteger tempIndex = new MutableInteger();

        while (!exceptionHandlers.isEmpty()) {
            final TryCatchBlock tryCatchBlock = new TryCatchBlock();
            final int minTryStart = body.get(headStartIndex).offset;

            //
            // Find the first and widest scope;
            //

            int tryStart = Integer.MAX_VALUE;
            int tryEnd = -1;
            int firstHandlerStart = -1;

            headStartIndex = tailStartIndex;

            for (final ExceptionHandler handler : exceptionHandlers) {
                final int start = handler.getTryBlock().getFirstInstruction().getOffset();

                if (start < tryStart && start >= minTryStart) {
                    tryStart = start;
                }
            }

            for (final ExceptionHandler handler : exceptionHandlers) {
                final int start = handler.getTryBlock().getFirstInstruction().getOffset();

                if (start == tryStart) {
                    final Instruction lastInstruction = handler.getTryBlock().getLastInstruction();
                    final int end = lastInstruction.getEndOffset();

                    if (end > tryEnd) {
                        tryEnd = end;

                        final int handlerStart = handler.getHandlerBlock().getFirstInstruction().getOffset();

                        if (firstHandlerStart < 0 || handlerStart < firstHandlerStart) {
                            firstHandlerStart = handlerStart;
                        }
                    }
                }
            }

            final ArrayList<ExceptionHandler> handlers = new ArrayList<>();

            for (final ExceptionHandler handler : exceptionHandlers) {
                final int start = handler.getTryBlock().getFirstInstruction().getOffset();
                final int end = handler.getTryBlock().getLastInstruction().getEndOffset();

                if (start == tryStart && end == tryEnd) {
                    handlers.add(handler);
                }
            }

            Collections.sort(
                handlers,
                new Comparator<ExceptionHandler>() {
                    @Override
                    public int compare(@NotNull final ExceptionHandler o1, @NotNull final ExceptionHandler o2) {
                        return Integer.compare(
                            o1.getTryBlock().getFirstInstruction().getOffset(),
                            o2.getTryBlock().getFirstInstruction().getOffset()
                        );
                    }
                }
            );

            //
            // Remember that any part of the body might have been removed due to unreachability.
            //

            //
            // Cut all instructions up to the try block.
            //
            int tryStartIndex = 0;

            while (tryStartIndex < body.size() &&
                   body.get(tryStartIndex).offset < tryStart) {

                tryStartIndex++;
            }

            if (headStartIndex < tryStartIndex) {
                ast.addAll(convertToAst(body.subList(headStartIndex, tryStartIndex)));
            }

            //
            // Cut the try block.
            //
            {
                final Set<ExceptionHandler> nestedHandlers = new LinkedHashSet<>();

                for (final ExceptionHandler eh : exceptionHandlers) {
                    final int ts = eh.getTryBlock().getFirstInstruction().getOffset();
                    final int te = eh.getTryBlock().getLastInstruction().getEndOffset();

                    if (tryStart < ts && te <= tryEnd || tryStart <= ts && te < tryEnd) {
                        nestedHandlers.add(eh);
                    }
                }

                exceptionHandlers.removeAll(nestedHandlers);

                int tryEndIndex = 0;

                while (tryEndIndex < body.size() && body.get(tryEndIndex).offset < tryEnd) {
                    tryEndIndex++;
                }

                final Block tryBlock = new Block();
/*
                for (int i = 0; i < tryEndIndex; i++) {
                    body.remove(0);
                }
*/

                tempIndex.setValue(tryEndIndex);

                final List<Node> tryAst = convertToAst(body, nestedHandlers, tryStartIndex, tempIndex);

                if (tempIndex.getValue() > tailStartIndex) {
                    tailStartIndex = tempIndex.getValue();
                }

                final Node lastInTry = lastOrDefault(tryAst);

                if (lastInTry != null && !lastInTry.isUnconditionalControlFlow()) {
                    tryAst.add(new Expression(AstCode.Leave, null));
                }

                tryBlock.getBody().addAll(tryAst);
                tryCatchBlock.setTryBlock(tryBlock);
                tailStartIndex = Math.max(tryEndIndex, tailStartIndex);
            }

            //
            // Cut from the end of the try to the beginning of the first handler.
            //

/*
            while (!body.isEmpty() && body.get(0).offset < firstHandlerStart) {
                body.remove(0);
            }
*/

            //
            // Cut all handlers.
            //
        HandlerLoop:
            for (final ExceptionHandler eh : handlers) {
                final TypeReference catchType = eh.getCatchType();
                final ExceptionBlock handlerBlock = eh.getHandlerBlock();

                final int handlerStart = handlerBlock.getFirstInstruction().getOffset();

                final int handlerEnd = handlerBlock.getLastInstruction() != null
                                       ? handlerBlock.getLastInstruction().getEndOffset()
                                       : _body.getCodeSize();

                int handlersStartIndex = 0;

                while (handlersStartIndex < body.size() &&
                       body.get(handlersStartIndex).offset < handlerStart) {

                    handlersStartIndex++;
                }

                int handlersEndIndex = handlersStartIndex;

                while (handlersEndIndex < body.size() &&
                       body.get(handlersEndIndex).offset < handlerEnd) {

                    handlersEndIndex++;
                }

                tailStartIndex = Math.max(tailStartIndex, handlersEndIndex);

                //
                // See if we share a block with another handler; if so, add our catch type and move on.
                //
                for (final CatchBlock catchBlock : tryCatchBlock.getCatchBlocks()) {
                    final Expression firstExpression = firstOrDefault(
                        catchBlock.getSelfAndChildrenRecursive(Expression.class),
                        new Predicate<Expression>() {
                            @Override
                            public boolean test(final Expression e) {
                                return !e.getRanges().isEmpty();
                            }
                        }
                    );

                    if (firstExpression == null) {
                        continue;
                    }

                    final int otherHandlerStart = firstExpression.getRanges().get(0).getStart();

                    if (otherHandlerStart == handlerStart) {
                        catchBlock.getCaughtTypes().add(catchType);

                        catchBlock.setExceptionType(
                            MetadataHelper.findCommonSuperType(
                                catchBlock.getExceptionType(),
                                catchType
                            )
                        );

                        continue HandlerLoop;
                    }
                }

                final Set<ExceptionHandler> nestedHandlers = new LinkedHashSet<>();

                for (final ExceptionHandler e : exceptionHandlers) {
                    final int ts = e.getTryBlock().getFirstInstruction().getOffset();
                    final int te = e.getTryBlock().getLastInstruction().getOffset();

                    if (ts == tryStart && te == tryEnd || e == eh) {
                        continue;
                    }

                    if (handlerStart <= ts && te < handlerEnd) {
                        nestedHandlers.add(e);

                        final int nestedEndIndex = firstIndexWhere(
                            body,
                            new Predicate<ByteCode>() {
                                @Override
                                public boolean test(final ByteCode code) {
                                    return code.instruction == e.getHandlerBlock().getLastInstruction();
                                }
                            }
                        );

                        if (nestedEndIndex > handlersEndIndex) {
                            handlersEndIndex = nestedEndIndex;
                        }
                    }
                }

                tailStartIndex = Math.max(tailStartIndex, handlersEndIndex);
                exceptionHandlers.removeAll(nestedHandlers);

                tempIndex.setValue(handlersEndIndex);

                final List<Node> handlerAst = convertToAst(body, nestedHandlers, handlersStartIndex, tempIndex);
                final Node lastInHandler = lastOrDefault(handlerAst);

                if (tempIndex.getValue() > tailStartIndex) {
                    tailStartIndex = tempIndex.getValue();
                }

                if (lastInHandler != null && !lastInHandler.isUnconditionalControlFlow()) {
                    handlerAst.add(new Expression(AstCode.Leave, null));
                }

                final ByteCode loadException = _loadExceptions.get(eh);

                if (eh.isCatch()) {
                    final CatchBlock catchBlock = new CatchBlock();

                    catchBlock.setExceptionType(catchType);
                    catchBlock.getCaughtTypes().add(catchType);
                    catchBlock.getBody().addAll(handlerAst);

                    if (loadException.storeTo == null || loadException.storeTo.isEmpty()) {
                        //
                        // Exception is not used.
                        //
                        catchBlock.setExceptionVariable(null);
                    }
                    else if (loadException.storeTo.size() == 1) {
                        if (!catchBlock.getBody().isEmpty() &&
                            catchBlock.getBody().get(0) instanceof Expression &&
                            !((Expression) catchBlock.getBody().get(0)).getArguments().isEmpty()) {

                            final Expression first = (Expression) catchBlock.getBody().get(0);
                            final AstCode firstCode = first.getCode();
                            final Expression firstArgument = first.getArguments().get(0);

                            if (firstCode == AstCode.Pop &&
                                firstArgument.getCode() == AstCode.Load &&
                                firstArgument.getOperand() == loadException.storeTo.get(0)) {

                                //
                                // The exception is just popped; optimize it away.
                                //
                                if (_context.getSettings().getAlwaysGenerateExceptionVariableForCatchBlocks()) {
                                    final Variable exceptionVariable = new Variable();

                                    exceptionVariable.setName(format("ex_%1$02X", handlerStart));
                                    exceptionVariable.setGenerated(true);

                                    catchBlock.setExceptionVariable(exceptionVariable);
                                }
                                else {
                                    catchBlock.setExceptionVariable(null);
                                }
                            }
                            else {
                                catchBlock.setExceptionVariable(loadException.storeTo.get(0));
                            }
                        }
                    }
                    else {
                        final Variable exceptionTemp = new Variable();

                        exceptionTemp.setName(format("ex_%1$02X", handlerStart));
                        exceptionTemp.setGenerated(true);

                        catchBlock.setExceptionVariable(exceptionTemp);

                        for (final Variable storeTo : loadException.storeTo) {
                            catchBlock.getBody().add(
                                0,
                                new Expression(AstCode.Store, storeTo, new Expression(AstCode.Load, exceptionTemp))
                            );
                        }
                    }

                    tryCatchBlock.getCatchBlocks().add(catchBlock);
                }
                else if (eh.isFinally()) {
                    final Block finallyBlock = new Block();

                    finallyBlock.getBody().addAll(handlerAst);
                    tryCatchBlock.setFinallyBlock(finallyBlock);

                    final Variable exceptionTemp = new Variable();

                    exceptionTemp.setName(format("ex_%1$02X", handlerStart));
                    exceptionTemp.setGenerated(true);

                    if (loadException == null || loadException.storeTo == null) {
                        final Expression finallyStart = firstOrDefault(finallyBlock.getSelfAndChildrenRecursive(Expression.class));

                        if (match(finallyStart, AstCode.Store)) {
                            finallyStart.getArguments().set(
                                0,
                                new Expression(AstCode.Load, exceptionTemp)
                            );
                        }
                    }
                    else {
                        for (final Variable storeTo : loadException.storeTo) {
                            finallyBlock.getBody().add(
                                0,
                                new Expression(AstCode.Store, storeTo, new Expression(AstCode.Load, exceptionTemp))
                            );
                        }
                    }

                    finallyBlock.getBody().add(
                        0,
                        new Expression(
                            AstCode.Store,
                            exceptionTemp,
                            new Expression(
                                AstCode.LoadException,
                                _factory.makeNamedType("java.lang.Throwable")
                            )
                        )
                    );
                }
            }

            exceptionHandlers.removeAll(handlers);

            final Expression first;
            final Expression last;

            first = firstOrDefault(tryCatchBlock.getTryBlock().getSelfAndChildrenRecursive(Expression.class));

            if (!tryCatchBlock.getCatchBlocks().isEmpty()) {
                final CatchBlock lastCatch = lastOrDefault(tryCatchBlock.getCatchBlocks());
                if (lastCatch == null) {
                    last = null;
                }
                else {
                    last = lastOrDefault(lastCatch.getSelfAndChildrenRecursive(Expression.class));
                }
            }
            else {
                final Block finallyBlock = tryCatchBlock.getFinallyBlock();
                if (finallyBlock == null) {
                    last = null;
                }
                else {
                    last = lastOrDefault(finallyBlock.getSelfAndChildrenRecursive(Expression.class));
                }
            }

            if (first == null && last == null) {
                //
                // Ignore empty handlers.  These can crop up due to finally blocks which handle themselves.
                //
                continue;
            }

            ast.add(tryCatchBlock);
        }

        if (tailStartIndex < endIndex.getValue()) {
            ast.addAll(convertToAst(body.subList(tailStartIndex, endIndex.getValue())));
        }
        else {
            endIndex.setValue(tailStartIndex);
        }

        return ast;
    }

    @SuppressWarnings("ConstantConditions")
    private List<Node> convertToAst(final List<ByteCode> body) {
        final ArrayList<Node> ast = new ArrayList<>();

        //
        // Convert stack-based bytecode to bytecode AST.
        //
        for (final ByteCode byteCode : body) {
            final Instruction originalInstruction = _originalInstructionMap.get(byteCode.instruction);
            final Range codeRange = new Range(originalInstruction.getOffset(), originalInstruction.getEndOffset());

            if (byteCode.stackBefore == null /*|| _removed.contains(byteCode.instruction)*/) {
                //
                // Unreachable code.
                //
                continue;
            }

            //
            // Include the instruction's label, if it has one.
            //
            if (byteCode.label != null) {
                ast.add(byteCode.label);
            }

            switch (byteCode.code) {
                case Dup:
                case DupX1:
                case DupX2:
                case Dup2:
                case Dup2X1:
                case Dup2X2:
                case Swap:
                    continue;
            }

            final Expression expression = new Expression(byteCode.code, byteCode.operand);

            if (byteCode.code == AstCode.Inc) {
                assert byteCode.secondOperand instanceof Integer;

                expression.setCode(AstCode.Inc);
                expression.getArguments().add(new Expression(AstCode.LdC, byteCode.secondOperand));
            }

            expression.getRanges().add(codeRange);

            //
            // Reference arguments using temporary variables.
            //

            final int popCount = byteCode.popCount != -1 ? byteCode.popCount
                                                         : byteCode.stackBefore.length;

            for (int i = byteCode.stackBefore.length - popCount; i < byteCode.stackBefore.length; i++) {
                final StackSlot slot = byteCode.stackBefore[i];

                if (slot.value.getType().isDoubleWord()) {
                    i++;
                }

                expression.getArguments().add(new Expression(AstCode.Load, slot.loadFrom));
            }

            //
            // Store the result to temporary variables, if needed.
            //
            if (byteCode.storeTo == null || byteCode.storeTo.isEmpty()) {
                ast.add(expression);
            }
            else if (byteCode.storeTo.size() == 1) {
                ast.add(new Expression(AstCode.Store, byteCode.storeTo.get(0), expression));
            }
            else {
                final Variable tempVariable = new Variable();

                tempVariable.setName(format("expr_%1$02X", byteCode.offset));
                tempVariable.setGenerated(true);

                ast.add(new Expression(AstCode.Store, tempVariable, expression));

                for (int i = byteCode.storeTo.size() - 1; i >= 0; i--) {
                    ast.add(
                        new Expression(
                            AstCode.Store,
                            byteCode.storeTo.get(i),
                            new Expression(AstCode.Load, tempVariable)
                        )
                    );
                }
            }
        }

        return ast;
    }

    // <editor-fold defaultstate="collapsed" desc="StackSlot Class">

    private final static class StackSlot {
        final FrameValue value;
        final ByteCode[] definitions;
        final Variable loadFrom;

        public StackSlot(final FrameValue value, final ByteCode[] definitions) {
            this.value = VerifyArgument.notNull(value, "value");
            this.definitions = VerifyArgument.notNull(definitions, "definitions");
            this.loadFrom = null;
        }

        public StackSlot(final FrameValue value, final ByteCode[] definitions, final Variable loadFrom) {
            this.value = VerifyArgument.notNull(value, "value");
            this.definitions = VerifyArgument.notNull(definitions, "definitions");
            this.loadFrom = loadFrom;
        }

        public static StackSlot[] modifyStack(
            final StackSlot[] stack,
            final int popCount,
            final ByteCode pushDefinition,
            final FrameValue... pushTypes) {

            VerifyArgument.notNull(stack, "stack");
            VerifyArgument.isNonNegative(popCount, "popCount");
            VerifyArgument.noNullElements(pushTypes, "pushTypes");

            final StackSlot[] newStack = new StackSlot[stack.length - popCount + pushTypes.length];

            System.arraycopy(stack, 0, newStack, 0, stack.length - popCount);

            for (int i = stack.length - popCount, j = 0; i < newStack.length; i++, j++) {
                newStack[i] = new StackSlot(pushTypes[j], new ByteCode[] { pushDefinition });
            }

            return newStack;
        }

        @Override
        public String toString() {
            return "StackSlot(" + value + ')';
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="VariableSlot Class">

    private final static class VariableSlot {
        final static VariableSlot UNKNOWN_INSTANCE = new VariableSlot(FrameValue.UNINITIALIZED, EMPTY_DEFINITIONS);

        final ByteCode[] definitions;
        final FrameValue value;

        public VariableSlot(final FrameValue value, final ByteCode[] definitions) {
            this.value = VerifyArgument.notNull(value, "value");
            this.definitions = VerifyArgument.notNull(definitions, "definitions");
        }

        public static VariableSlot[] cloneVariableState(final VariableSlot[] state) {
            return VerifyArgument.notNull(state, "state").clone();
        }

        public static VariableSlot[] makeUnknownState(final int variableCount) {
            final VariableSlot[] unknownVariableState = new VariableSlot[variableCount];

            for (int i = 0; i < variableCount; i++) {
                unknownVariableState[i] = UNKNOWN_INSTANCE;
            }

            return unknownVariableState;
        }

        public final boolean isUninitialized() {
            return value == FrameValue.UNINITIALIZED || value == FrameValue.UNINITIALIZED_THIS;
        }
    }

    // </editor-fold>

    // <editor-fold defaultstate="collapsed" desc="ByteCode Class">

    private final static class ByteCode {
        Label label;
        Instruction instruction;
        String name;
        int offset; // NOTE: If you change 'offset', clear out 'name'.
        int endOffset;
        AstCode code;
        Object operand;
        Object secondOperand;
        int popCount = -1;
        int pushCount;
        ByteCode next;
        ByteCode previous;
        FrameValue type;
        StackSlot[] stackBefore;
        VariableSlot[] variablesBefore;
        List<Variable> storeTo;

        public final String name() {
            if (name == null) {
                name = format("#%1$04d", offset);
            }
            return name;
        }

        public final String makeLabelName() {
            return format("Label_%1$04d", offset);
        }

        public final Frame getFrameBefore() {
            final FrameValue[] stackValues;
            final FrameValue[] variableValues;

            if (stackBefore.length == 0) {
                stackValues = FrameValue.EMPTY_VALUES;
            }
            else {
                stackValues = new FrameValue[stackBefore.length];

                for (int i = 0; i < stackBefore.length; i++) {
                    stackValues[i] = stackBefore[i].value;
                }
            }
            if (variablesBefore.length == 0) {
                variableValues = FrameValue.EMPTY_VALUES;
            }
            else {
                variableValues = new FrameValue[variablesBefore.length];

                for (int i = 0; i < variablesBefore.length; i++) {
                    variableValues[i] = variablesBefore[i].value;
                }
            }

            return new Frame(FrameType.New, variableValues, stackValues);
        }

        public final boolean isVariableDefinition() {
            return code == AstCode.Store/* ||
                   code == AstCode.Inc*/;
        }

        @Override
        @SuppressWarnings("ConstantConditions")
        public final String toString() {
            final StringBuilder sb = new StringBuilder();

            //
            // Label
            //
            sb.append(name()).append(':');

            if (label != null) {
                sb.append('*');
            }

            //
            // Name
            //
            sb.append(' ');
            sb.append(code.getName());

            if (operand != null) {
                sb.append(' ');

                if (operand instanceof Instruction) {
                    sb.append(format("#%1$04d", ((Instruction) operand).getOffset()));
                }
                else if (operand instanceof Instruction[]) {
                    for (final Instruction instruction : (Instruction[]) operand) {
                        sb.append(format("#%1$04d", instruction.getOffset()));
                        sb.append(' ');
                    }
                }
                else if (operand instanceof Label) {
                    sb.append(((Label) operand).getName());
                }
                else if (operand instanceof Label[]) {
                    for (final Label l : (Label[]) operand) {
                        sb.append(l.getName());
                        sb.append(' ');
                    }
                }
                else if (operand instanceof VariableReference) {
                    final VariableReference variable = (VariableReference) operand;

                    if (variable.hasName()) {
                        sb.append(variable.getName());
                    }
                    else {
                        sb.append("$" + String.valueOf(variable.getSlot()));
                    }
                }
                else {
                    sb.append(operand);
                }
            }

            if (stackBefore != null) {
                sb.append(" StackBefore={");

                for (int i = 0; i < stackBefore.length; i++) {
                    if (i != 0) {
                        sb.append(',');
                    }

                    final StackSlot slot = stackBefore[i];
                    final ByteCode[] definitions = slot.definitions;

                    for (int j = 0; j < definitions.length; j++) {
                        if (j != 0) {
                            sb.append('|');
                        }
                        sb.append(format("#%1$04d", definitions[j].offset));
                    }
                }

                sb.append('}');
            }

            if (storeTo != null && !storeTo.isEmpty()) {
                sb.append(" StoreTo={");

                for (int i = 0; i < storeTo.size(); i++) {
                    if (i != 0) {
                        sb.append(',');
                    }
                    sb.append(storeTo.get(i).getName());
                }

                sb.append('}');
            }

            if (variablesBefore != null) {
                sb.append(" VariablesBefore={");

                for (int i = 0; i < variablesBefore.length; i++) {
                    if (i != 0) {
                        sb.append(',');
                    }

                    final VariableSlot slot = variablesBefore[i];

                    if (slot.isUninitialized()) {
                        sb.append('?');
                    }
                    else {
                        final ByteCode[] definitions = slot.definitions;
                        for (int j = 0; j < definitions.length; j++) {
                            if (j != 0) {
                                sb.append('|');
                            }
                            sb.append(format("#%1$04d", definitions[j].offset));
                        }
                    }
                }

                sb.append('}');
            }

            return sb.toString();
        }
    }

    // </editor-fold>
}
