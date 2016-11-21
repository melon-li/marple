package edu.mit.needlstk;
import java.util.List;
import java.util.ArrayList;

/// Code consists of declarations and statements.
public class ThreeOpCode {
  List<ThreeOpDecl> decls;
  List<ThreeOpStmt> stmts;

  /// Constructor
  public ThreeOpCode(ArrayList<ThreeOpDecl> decls, ArrayList<ThreeOpStmt> stmts) {
    this.decls = decls;
    this.stmts = stmts;
  }

  /// Default constructor
  public ThreeOpCode() {
    this.decls = new ArrayList<ThreeOpDecl>();
    this.stmts = new ArrayList<ThreeOpStmt>();
  }

  /// Merge the argument TOC with the current TOC, and return a new TOC.
  public ThreeOpCode orderedMerge(ThreeOpCode other) {
    return new ThreeOpCode(decls.addAll(other.decls), stmts.addAll(other.stmts));
  }

  public String print() {
    String res = "Declarations:\n";
    for (ThreeOpDecl decl: decls) {
      res += decl.print();
      res += "\n";
    }
    res += "Statements:\n";
    for (ThreeOpStmt stmt: stmts) {
      res += stmt.print();
      res += "\n";
    }
    return res;
  }
}