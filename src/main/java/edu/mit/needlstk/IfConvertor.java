package edu.mit.needlstk;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Collections;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

/// Convert aggregation function into three-operand code for processing both by the P4 as well as
/// domino compiler.
public class IfConvertor extends PerfQueryBaseVisitor<ThreeOpCode> {
  /// Unique integer instance
  private Integer incr = 0;
  /// HashMap that maintains an "outer" predicate for each statement
  private HashMap<PerfQueryParser.ParserRuleContext, String> outerPredIdMap;
  private HashMap<PerfQueryParser.ParserRuleContext, AugPred> outerPredTreeMap;
  /// Default integer bitwidth used for declarations in emitted code.
  private Integer INT_WIDTH = 32;

  /// Constructor
  public IfConvertor() {
    this.outerPredIdMap = new IdentityHashMap<PerfQueryParser.ParserRuleContext, String>();
    this.outerPredTreeMap = new IdentityHashMap<PerfQueryParser.ParserRuleContext, AugPred>();
  }

  /// Get unique integers across all instances of this class. NOT thread-safe.
  private String getUid() {
    incr += 1;
    return incr.toString();
  }

  /// ANTLR visitor for primitives
  public ThreeOpCode visitPrimitive(PerfQueryParser.PrimitiveContext ctx) {
    assert(outerPredIdMap.containsKey(ctx));
    ThreeOpStmt stmt = new ThreeOpStmt(ctx.ID().getText(),
                                       outerPredIdMap.get(ctx),
                                       new AugExpr(ctx.expr()),
                                       new AugExpr(ctx.ID()));
    return new ThreeOpCode(new ArrayList<ThreeOpDecl>(),
                           new ArrayList<ThreeOpStmt>(Arrays.asList(stmt)));
  }

  /// ANTLR visitor for ifConstruct
  public ThreeOpCode visitIfConstruct(PerfQueryParser.IfConstructContext ctx) {
    assert(outerPredIdMap.containsKey(ctx));
    assert(outerPredTreeMap.containsKey(ctx));
    AugPred outerPred = outerPredTreeMap.get(ctx);
    AugPred currPred = new AugPred(ctx.predicate());
    ThreeOpCode code = handleIfOrElse(ctx.ifPrimitive(), currPred, outerPred, true);
    if(ctx.elsePrimitive().size() > 0) {
      code = code.orderedMerge(handleIfOrElse(ctx.elsePrimitive(), currPred, outerPred, false));
    }
    return code;
  }

  /// ANTLR visitor for aggFun
  public ThreeOpCode visitAggFun(PerfQueryParser.AggFunContext ctx) {
    List<StmtContext> stmts = ctx.stmt();
    ThreeOpCode toc = new ThreeOpCode();
    for (stmt: stmts) {
      toc = toc.orderedMerge(visit(stmt));
    }
    toc.print();
    return toc;
  }

  /// Helper to handle one part of an IF ... ELSE statement: either the IF or ELSE. The logic is
  /// very similar for the two, so they can be handled through the same function.
  private ThreeOpCode handleIfOrElse(List<PerfQueryParser.ParserRuleContext> ctxList,
                                     AugPred currPred,
                                     AugPred outerPred,
                                     bool ifPred) {
    /// step 1. Get a new predicate corresponding to this case.
    /// 1.1 Declare a new predicate variable
    String predVarId = "pred_" + getUid();
    ThreeOpDecl predVarDecl = new ThreeOpDecl(INT_WIDTH, uid);
    /// 1.2 Assign new predicate variable to clausePred
    AugPred clausePred = outerPred.and(ifPred ? currPred : currPred.not());
    ThreeOpStmt predVarStmt = new ThreeOpStmt(predVarId, clausePred);
    /// step 2. Merge three operand codes from each internal statement
    ThreeOpCode toc = new ThreeOpCode(
        new ArrayList<>(Arrays.asList(predVarDecl)),
        new ArrayList<>(Arrays.asList(predVarStmt)));
    for (ctx : ctxList) {
      outerPredIdMap.put(ctx, predVarId);
      outerPredTreeMap.put(ctx, clausePred);
      toc = toc.orderedMerge(visit(ctx));
    }
    return toc;
  }
}
