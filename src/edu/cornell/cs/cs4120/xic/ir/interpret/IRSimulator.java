package edu.cornell.cs.cs4120.xic.ir.interpret;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.util.*;

import edu.cornell.cs.cs4120.util.InternalCompilerError;
import edu.cornell.cs.cs4120.xic.ir.IRBinOp;
import edu.cornell.cs.cs4120.xic.ir.IRCJump;
import edu.cornell.cs.cs4120.xic.ir.IRCall;
import edu.cornell.cs.cs4120.xic.ir.IRCompUnit;
import edu.cornell.cs.cs4120.xic.ir.IRConst;
import edu.cornell.cs.cs4120.xic.ir.IRExp;
import edu.cornell.cs.cs4120.xic.ir.IRFuncDecl;
import edu.cornell.cs.cs4120.xic.ir.IRJump;
import edu.cornell.cs.cs4120.xic.ir.IRMem;
import edu.cornell.cs.cs4120.xic.ir.IRMove;
import edu.cornell.cs.cs4120.xic.ir.IRName;
import edu.cornell.cs.cs4120.xic.ir.IRNode;
import edu.cornell.cs.cs4120.xic.ir.IRReturn;
import edu.cornell.cs.cs4120.xic.ir.IRTemp;
import edu.cornell.cs.cs4120.xic.ir.visit.InsnMapsBuilder;
import polyglot.util.SerialVersionUID;

/**
 * A simple IR interpreter
 */
public class IRSimulator {
    /** compilation unit to be interpreted */
    private IRCompUnit compUnit;

    /** map from address to instruction */
    protected Map<Long, IRNode> indexToInsn;

    /** map from labeled name to address */
    private Map<String, Long> nameToIndex;

    /** a random number generator for initializing garbage */
    protected Random r;

    /** heap */
    private long mem[];

    /** heap pointer to lowest unallocated region */
    private long heapPtr;

    private ExprStack exprStack;
    private BufferedReader inReader;

    private Set<String> libraryFunctions;
    private List<String> ctors;

    protected static int debugLevel = 0;

    public static final int DEFAULT_HEAP_SIZE = 10240;

    /**
     * Construct an IR interpreter with a default heap size
     * @param compUnit the compilation unit to be interpreted
     */
    public IRSimulator(IRCompUnit compUnit) {
        this(compUnit, DEFAULT_HEAP_SIZE);
    }

    /**
     * Construct an IR interpreter
     * @param compUnit the compilation unit to be interpreted
     * @param heapSize the heap size
     */
    public IRSimulator(IRCompUnit compUnit, int heapSize) {
        this.compUnit = compUnit;

        r = new Random();

        mem = new long[heapSize];
        // initialize heap to garbage
        for (int i = 0; i < heapSize; i++)
            mem[i] = r.nextLong();
        // initialize heap pointer
        heapPtr = 0;

        exprStack = new ExprStack();
        inReader = new BufferedReader(new InputStreamReader(System.in));

        libraryFunctions = new LinkedHashSet<>();
        // io declarations
        libraryFunctions.add("_Iprint_pai");
        libraryFunctions.add("_Iprintln_pai");
        libraryFunctions.add("_Ireadln_ai");
        libraryFunctions.add("_Igetchar_i");
        libraryFunctions.add("_Ieof_b");
        // conv declarations
        libraryFunctions.add("_IparseInt_t2ibai");
        libraryFunctions.add("_IunparseInt_aii");
        // special declarations
        libraryFunctions.add("_xi_alloc");
        libraryFunctions.add("_xi_out_of_bounds");
        // other declarations
        libraryFunctions.add("_Iassert_pb");

        InsnMapsBuilder imb = new InsnMapsBuilder();
        compUnit = (IRCompUnit) imb.visit(compUnit);
        indexToInsn = imb.indexToInsn();
        nameToIndex = imb.nameToIndex();
        ctors = imb.ctors();

        // set up a dummy constructor frame, so that the constructors
        //   have somewhere to write their return values to
        ExecutionFrame ctorFrame = new ExecutionFrame(0);
        for (int i = 0; i < ctors.size(); ++i)
            call(ctorFrame, ctors.get(i), new long[] {});
    }

    /**
     * Allocate a specified amount of bytes on the heap
     * @param size the number of bytes to be allocated
     * @return the starting address of the allocated region on the heap
     */
    public long malloc(long size) {
        if (size < 0) throw new Trap("Invalid size");
        if (size % Configuration.WORD_SIZE != 0)
            throw new Trap("Can only allocate in chunks of "
                    + Configuration.WORD_SIZE + " bytes!");

        long retval = heapPtr;
        heapPtr += size;
        if (heapPtr > mem.length) throw new Trap("Out of heap!");
        return retval;
    }

    /**
     * Read a value at the specified location on the heap
     * @param addr the address to be read
     * @return the value at {@code addr}
     */
    public long read(long addr) {
        return mem[(int) getMemoryIndex(addr)];
    }

    /**
     * Write a value at the specified location on the heap
     * @param addr the address to be written
     * @param value the value to be written
     */
    public void store(long addr, long value) {
        mem[(int) getMemoryIndex(addr)] = value;
    }

    protected long getMemoryIndex(long addr) {
        if (addr % Configuration.WORD_SIZE != 0)
            throw new Trap("Unaligned memory access: " + addr + " (word size="
                    + Configuration.WORD_SIZE + ")");
        return addr / Configuration.WORD_SIZE;
    }

    /**
     * Simulate a function call, throwing away all returned values past the first
     * All arguments to the function call are passed via registers with prefix
     * {@link Configuration#ABSTRACT_ARG_PREFIX} and indices starting from 0.
     * @param name name of the function call
     * @param args arguments to the function call
     * @return the value that would be in register
     *          {@link Configuration#ABSTRACT_RET_PREFIX} index 0
     */
    public long call(String name, long... args) {
        return call(new ExecutionFrame(-1), name, args);
    }

    /**
     * Simulate a function call.
     * All arguments to the function call are passed via registers with prefix
     * {@link Configuration#ABSTRACT_ARG_PREFIX} and indices starting from 0.
     * The function call should return the results via registers with prefix
     * {@link Configuration#ABSTRACT_RET_PREFIX} and indices starting from 0.
     * @param parent parent call frame to write _RET values to
     * @param name name of the function call
     * @param args arguments to the function call
     * @return the value of register
     *          {@link Configuration#ABSTRACT_RET_PREFIX} index 0
     */
    public long call(ExecutionFrame parent, String name, long... args) {
        final List<Long> ret;
        // Catch standard library calls.
        if (libraryFunctions.contains(name)) {
            ret = libraryCall(name, args);
        } else {
            IRFuncDecl fDecl = compUnit.getFunction(name);
            if (fDecl == null)
                throw new InternalCompilerError("Tried to call an unknown function: '"
                        + name + "'");

            // Create a new stack frame.
            long ip = findLabel(name);
            ExecutionFrame frame = new ExecutionFrame(ip);

            // Pass the remaining arguments into registers.
            for (int i = 0; i < args.length; ++i)
                frame.put(Configuration.ABSTRACT_ARG_PREFIX + i, args[i]);

            // Simulate!
            while (frame.advance()) ;

            ret = frame.rets;
        }

        for (int i = 0; i < ret.size(); i++) {
            parent.put(Configuration.ABSTRACT_RET_PREFIX + i, ret.get(i));
        }
        if (ret.size() > 0) {
            return ret.get(0);
        } else {
            return 0;
        }
    }

    /**
     * Simulate a library function call, returning the list of returned values
     * @param name name of the function call
     * @param args arguments to the function call, which may include
     *          the pointer to the location of multiple results
     */
    protected List<Long> libraryCall(String name, long[] args) {
        final int ws = Configuration.WORD_SIZE;
        final List<Long> ret = new ArrayList<>();
        try {
            switch (name) {
            // io declarations
            case "_Iprint_pai": {
                long ptr = args[0], size = read(ptr - ws);
                for (long i = 0; i < size; ++i)
                    System.out.print((char) read(ptr + i * ws));
                break;
            }
            case "_Iprintln_pai": {
                long ptr = args[0], size = read(ptr - ws);
                for (long i = 0; i < size; ++i)
                    System.out.print((char) read(ptr + i * ws));
                System.out.println();
                break;
            }
            case "_Ireadln_ai": {
                String line = inReader.readLine();
                int len = line.length();
                long ptr = malloc((len + 1) * ws);
                store(ptr, len);
                for (int i = 0; i < len; ++i)
                    store(ptr + (i + 1) * ws, line.charAt(i));
                ret.add(ptr + ws);
                break;
            }
            case "_Igetchar_i": {
                ret.add((long) inReader.read());
                break;
            }
            case "_Ieof_b": {
                ret.add((long) (inReader.ready() ? 0 : 1));
                break;
            }
            // conv declarations
            case "_IunparseInt_aii": {
                String line = String.valueOf(args[0]);
                int len = line.length();
                long ptr = malloc((len + 1) * ws);
                store(ptr, len);
                for (int i = 0; i < len; ++i)
                    store(ptr + (i + 1) * ws, line.charAt(i));
                ret.add(ptr + ws);
                break;
            }
            case "_IparseInt_t2ibai": {
                StringBuffer buf = new StringBuffer();
                long ptr = args[0], size = read(ptr - ws);
                for (int i = 0; i < size; ++i)
                    buf.append((char) read(ptr + i * ws));
                long result = 0, success = 1;
                try {
                    result = Integer.parseInt(buf.toString());
                }
                catch (NumberFormatException e) {
                    success = 0;
                }
                ret.add(result);
                ret.add(success);
                break;
            }
            // special declarations
            case "_xi_alloc": {
                ret.add(malloc(args[0]));
                break;
            }
            case "_xi_out_of_bounds": {
                throw new OutOfBoundTrap("Out of bounds!");
            }
            // other declarations
            case "_Iassert_pb": {
                if (args[0] != 1) throw new Trap("Assertion error!");
                break;
            }
            default:
                throw new InternalCompilerError("Unsupported library function: "
                        + name);
            }

            return ret;
        }
        catch (IOException e) {
            throw new InternalCompilerError("I/O Exception in simulator");
        }
    }

    protected void leave(ExecutionFrame frame) {
        IRNode insn = frame.getCurrentInsn();
        if (insn instanceof IRConst)
            exprStack.pushValue(((IRConst) insn).value());
        else if (insn instanceof IRTemp) {
            String tempName = ((IRTemp) insn).name();
            exprStack.pushTemp(frame.get(tempName), tempName);
        }
        else if (insn instanceof IRBinOp) {
            long r = exprStack.popValue();
            long l = exprStack.popValue();
            long result;
            switch (((IRBinOp) insn).opType()) {
            case ADD:
                result = l + r;
                break;
            case SUB:
                result = l - r;
                break;
            case MUL:
                result = l * r;
                break;
            case HMUL:
                result = BigInteger.valueOf(l)
                                   .multiply(BigInteger.valueOf(r))
                                   .shiftRight(64)
                                   .longValue();
                break;
            case DIV:
                if (r == 0) throw new Trap("Division by zero!");
                result = l / r;
                break;
            case MOD:
                if (r == 0) throw new Trap("Division by zero!");
                result = l % r;
                break;
            case AND:
                result = l & r;
                break;
            case OR:
                result = l | r;
                break;
            case XOR:
                result = l ^ r;
                break;
            case LSHIFT:
                result = l << r;
                break;
            case RSHIFT:
                result = l >>> r;
                break;
            case ARSHIFT:
                result = l >> r;
                break;
            case EQ:
                result = l == r ? 1 : 0;
                break;
            case NEQ:
                result = l != r ? 1 : 0;
                break;
            case LT:
                result = l < r ? 1 : 0;
                break;
            case GT:
                result = l > r ? 1 : 0;
                break;
            case LEQ:
                result = l <= r ? 1 : 0;
                break;
            case GEQ:
                result = l >= r ? 1 : 0;
                break;
            default:
                throw new InternalCompilerError("Invalid binary operation");
            }
            exprStack.pushValue(result);
        }
        else if (insn instanceof IRMem) {
            long addr = exprStack.popValue();
            exprStack.pushAddr(read(addr), addr);
        }
        else if (insn instanceof IRCall) {
            int argsCount = ((IRCall) insn).args().size();
            long args[] = new long[argsCount];
            for (int i = argsCount - 1; i >= 0; --i)
                args[i] = exprStack.popValue();
            StackItem target = exprStack.pop();
            String targetName;
            if (target.type == StackItem.Kind.NAME)
                targetName = target.name;
            else if (indexToInsn.containsKey(target.value)) {
                IRNode node = indexToInsn.get(target.value);
                if (node instanceof IRFuncDecl)
                    targetName = ((IRFuncDecl) node).name();
                else throw new InternalCompilerError("Call to a non-function instruction!");
            }
            else throw new InternalCompilerError("Invalid function call '"
                    + insn + "' (target '" + target.value + "' is unknown)!");

            long retVal = call(frame, targetName, args);
            exprStack.pushValue(retVal);
        }
        else if (insn instanceof IRName) {
            String name = ((IRName) insn).name();
            exprStack.pushName(libraryFunctions.contains(name)
                    ? -1 : findLabel(name), name);
        }
        else if (insn instanceof IRMove) {
            long r = exprStack.popValue();
            StackItem stackItem = exprStack.pop();
            switch (stackItem.type) {
            case MEM:
                if (debugLevel > 0)
                    System.out.println("mem[" + stackItem.addr + "]=" + r);
                store(stackItem.addr, r);
                break;
            case TEMP:
                if (debugLevel > 0)
                    System.out.println("temp[" + stackItem.temp + "]=" + r);
                frame.put(stackItem.temp, r);
                break;
            default:
                throw new InternalCompilerError("Invalid MOVE!");
            }
        }
        else if (insn instanceof IRExp) {
            // Discard result.
            exprStack.pop();
        }
        else if (insn instanceof IRJump)
            frame.setIP(exprStack.popValue());
        else if (insn instanceof IRCJump) {
            IRCJump irCJump = (IRCJump) insn;
            long top = exprStack.popValue();
            String label;
            if (top == 0)
                label = irCJump.falseLabel();
            else if (top == 1)
                label = irCJump.trueLabel();
            else throw new InternalCompilerError("Invalid value in CJUMP - expected 0/1, got "
                    + top);
            if (label != null) frame.setIP(findLabel(label));
        }
        else if (insn instanceof IRReturn) {
            int argsCount = ((IRReturn) insn).rets().size();
            // double pass for linear time
            long rets[] = new long[argsCount];
            for (int i = argsCount - 1; i >= 0; --i) {
                rets[i] = exprStack.popValue();
            }
            for (int i = 0; i < argsCount; i++) {
                frame.rets.add(rets[i]);
            }

            frame.setIP(-1);
        }
    }

    /**
     *
     * @param name name of the label
     * @return the IR node at the named label
     */
    private long findLabel(String name) {
        if (!nameToIndex.containsKey(name))
            throw new Trap("Could not find label '" + name + "'!");
        return nameToIndex.get(name);
    }

    /**
     * Holds the instruction pointer and temporary registers
     * within an execution frame.
     */
    private class ExecutionFrame {
        /** instruction pointer */
        protected long ip;

        /** return values from this frame */
        protected List<Long> rets;

        /** local registers (register name -> value) */
        private Map<String, Long> regs;


        public ExecutionFrame(long ip) {
            this.ip = ip;
            regs = new HashMap<>();
            rets = new ArrayList<>();
        }

        /**
         * Fetch the value at the given register
         * @param tempName name of the register
         * @return the value at the given register
         */
        public long get(String tempName) {
            if (!regs.containsKey(tempName)) {
                /* Referencing a temp before having written to it - initialize
                   with garbage */
                put(tempName, r.nextLong());
            }
            return regs.get(tempName);
        }

        /**
         * Store a value into the given register
         * @param tempName name of the register
         * @param value value to be stored
         */
        public void put(String tempName, long value) {
            regs.put(tempName, value);
        }

        /**
         * Advance the instruction pointer. Since we're dealing with a tree,
         * this is postorder traversal, one step at a time, modulo jumps.
         */
        public boolean advance() {
            // Time out if necessary.
            if (Thread.currentThread().isInterrupted()) return false;

            if (debugLevel > 1)
                System.out.println("Evaluating " + getCurrentInsn().label());
            long backupIP = ip;
            leave(this);

            if (ip == -1) return false; /* RETURN */

            if (ip != backupIP) /* A jump was performed */
                return true;

            ip++;
            return true;
        }

        public void setIP(long ip) {
            this.ip = ip;
            if (debugLevel > 1) {
                if (ip == -1)
                    System.out.println("Returning");
                else System.out.println("Jumping to "
                        + getCurrentInsn().label());
            }
        }

        public IRNode getCurrentInsn() {
            IRNode insn = indexToInsn.get(ip);
            if (insn == null)
                throw new Trap("No next instruction.  Forgot RETURN?");
            return insn;
        }
    };

    /**
     * While traversing the IR tree, we require a stack in order to hold
     * a number of single-word values (e.g., to evaluate binary expressions).
     * This also keeps track of whether a value was created by a TEMP
     * or MEM, or NAME reference, which is useful when executing moves.
     */
    private static class ExprStack {

        private Stack<StackItem> stack;

        public ExprStack() {
            stack = new Stack<>();
        }

        public long popValue() {
            long value = stack.pop().value;
            if (debugLevel > 1) System.out.println("Popping value " + value);
            return value;
        }

        public StackItem pop() {
            return stack.pop();
        }

        public void pushAddr(long value, long addr) {
            if (debugLevel > 1)
                System.out.println("Pushing MEM " + value + " (" + addr + ")");
            stack.push(new StackItem(value, addr));
        }

        public void pushTemp(long value, String temp) {
            if (debugLevel > 1)
                System.out.println("Pushing TEMP " + value + " (" + temp + ")");
            stack.push(new StackItem(StackItem.Kind.TEMP, value, temp));
        }

        public void pushName(long value, String name) {
            if (debugLevel > 1)
                System.out.println("Pushing NAME " + value + " (" + name + ")");
            stack.push(new StackItem(StackItem.Kind.NAME, value, name));
        }

        public void pushValue(long value) {
            if (debugLevel > 1) System.out.println("Pushing value " + value);
            stack.push(new StackItem(value));
        }
    }

    public static class StackItem {
        public enum Kind {
            COMPUTED, MEM, TEMP, NAME;
        }

        public Kind type;
        public long value;
        public long addr;
        public String temp;
        public String name;

        public StackItem(long value) {
            type = Kind.COMPUTED;
            this.value = value;
        }

        public StackItem(long value, long addr) {
            type = Kind.MEM;
            this.value = value;
            this.addr = addr;
        }

        public StackItem(Kind type, long value, String string) {
            this.type = type;
            this.value = value;
            if (type == Kind.TEMP)
                temp = string;
            else name = string;
        }
    };

    public static class Trap extends RuntimeException {
        private static final long serialVersionUID =
                SerialVersionUID.generate();

        public Trap(String message) {
            super(message);
        }
    };

    public static class OutOfBoundTrap extends Trap {
        private static final long serialVersionUID =
                SerialVersionUID.generate();

        public OutOfBoundTrap(String message) {
            super(message);
        }
    };
}
