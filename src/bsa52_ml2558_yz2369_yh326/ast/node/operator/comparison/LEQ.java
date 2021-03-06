package bsa52_ml2558_yz2369_yh326.ast.node.operator.comparison;

import bsa52_ml2558_yz2369_yh326.ast.node.operator.comparison.ArithmeticComparisonOperator;
import edu.cornell.cs.cs4120.xic.ir.IRBinOp;
import edu.cornell.cs.cs4120.xic.ir.IRExpr;
import edu.cornell.cs.cs4120.xic.ir.IRNode;
import edu.cornell.cs.cs4120.xic.ir.IRBinOp.OpType;

public class LEQ extends ArithmeticComparisonOperator {
    public LEQ(int line, int col) {
        super(line, col, "<=");
    }

    @Override
    public IRNode translateWithOperands(IRExpr... operands) {
        return new IRBinOp(OpType.LEQ, operands[0], operands[1]);
    }
}