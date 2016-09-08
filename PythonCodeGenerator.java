import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ErrorNode;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.misc.Interval;
import java.util.List;
import java.util.TreeSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.lang.RuntimeException;

public class PythonCodeGenerator extends perf_queryBaseListener {
  /// Intrinsic fields understood by the compiler
  private final TreeSet<String> intrinsic_fields_ = new TreeSet<String>(Arrays.asList("qid", "qin", "tin", "tout", "pkt_path", "switch_id", "uid"));

  /// Reference to parser to get underlying token stream.
  /// Required to preserve spaces and retrieve them when unparsing productions
  private perf_queryParser parser_;

  /// All fields in tuples,
  /// start with intrinsic fields alone
  private TreeSet<String> tuple_field_set_ = new TreeSet<String>(intrinsic_fields_);

  /// All fields in state
  private TreeSet<String> state_field_set_ = new TreeSet<String>();

  /// Build up function calls string
  private String function_calls_ = "";

  /// Build up function definitions string
  private String function_defs_ = "";

  /// Last assigned stream
  private String last_assigned_ = "";

  public PythonCodeGenerator(perf_queryParser t_parser, HashMap<String, IdentifierType> t_symbol_table) {
    parser_ = t_parser;
    
    for (String key : t_symbol_table.keySet()) {
      if (t_symbol_table.get(key) == IdentifierType.COLUMN) {
        tuple_field_set_.add(key);
      }
    }
    tuple_field_set_.add("valid");

    for (String key : t_symbol_table.keySet()) {
      if (t_symbol_table.get(key) == IdentifierType.STATE) {
        state_field_set_.add(key);
      }
    }
  }

  /// Use this to print declarations at the beginning of Python code
  /// For tuples and state
  @Override public void enterProg(perf_queryParser.ProgContext ctx) {
    System.err.println(generate_state_class());
    System.err.println(generate_tuple_class());
    System.err.println("def fuse(x, y):");
    System.err.println("  if (x is None) : return y;");
    System.err.println("  if (y is None) : return x;");
    System.err.println("  raise Exception (\"Can't fuse, both x and y are not None\")");
    System.err.println("def random_tuple():");
    System.err.println("  ret = Tuple();");
    for (String key : intrinsic_fields_) {
      System.err.println("  " + "ret." + key + " = random.randint(1, 65536);\n");
    }
    System.err.println("  return ret;");
  }

  /// Use this to print the packet loop that tests the given sql program
  /// at the end of the Python code
  @Override public void exitProg(perf_queryParser.ProgContext ctx) {
    System.err.println(function_defs_);
    System.err.println("# main loop of function calls");
    System.err.println("state_dict = dict();");
    System.err.println("for i in range(1000):");
    System.err.println("  T = random_tuple(); # generates a random tuple");
    System.err.println("  print \"input:\", T;");
    System.err.println("  T.print_tuple();");
    System.err.println(function_calls_);
    System.err.println("  print \"output:\"," + last_assigned_ + ";");
    System.err.println("  " + last_assigned_ + ".print_tuple();");
  }

  /// Turn aggregation function into a Python function definitoon
  @Override public void exitAgg_fun(perf_queryParser.Agg_funContext ctx) {
    System.err.println("def " + ctx.getChild(1).getText() + " (state, tuple_var):\n");
    System.err.println(generate_state_preamble());
    System.err.println(generate_tuple_preamble());
    System.err.println(process_code_block(ctx.stmt()) + "\n");
    System.err.println(generate_state_postamble());
    System.err.println(generate_tuple_postamble());
  }

  private String process_code_block(List<perf_queryParser.StmtContext> code_block) {
    String ret = "";
    for (int i = 0; i < code_block.size(); i++) {
      assert(code_block.get(i).getChildCount() == 1);
      assert(code_block.get(i).getChild(0) instanceof ParserRuleContext);
      ParserRuleContext single_stmt = (ParserRuleContext)code_block.get(i).getChild(0);
      if (single_stmt instanceof perf_queryParser.PrimitiveContext) {
        ret += "  " + process_primitive(single_stmt) + "\n";
      } else if (single_stmt instanceof perf_queryParser.If_constructContext) {
        perf_queryParser.If_constructContext if_stmt = (perf_queryParser.If_constructContext)single_stmt;
        ret += "  if " + text_with_spaces(if_stmt.pred()) + " : \n";
        for (int j = 0; j < if_stmt.if_primitive().size(); j++) {
          ret += "    " + process_primitive(if_stmt.if_primitive().get(j)) + "\n";
        }
        // Optional else
        if (if_stmt.ELSE() != null) {
          ret += "  else : \n";
          for (int j = 0; j < if_stmt.else_primitive().size(); j++) {
            ret += "    " + process_primitive(if_stmt.else_primitive().get(j)) + "\n";
          }
        }
      }
    }
    return ret;
  }

  private String process_primitive(ParserRuleContext single_stmt) {
    if (text_with_spaces(single_stmt).contains("emit()")) return emit_stub();
    else if (text_with_spaces(single_stmt).contains(";")) return "";
    else return text_with_spaces(single_stmt);
  }

  /// Turn selects into a Python function definition
  private String filter_def(ParseTree query, ParseTree stream) {
    ParserRuleContext predicate = (ParserRuleContext)(query.getChild(0).getChild(5));
    return (spg_query_signature(stream) +
            generate_tuple_preamble() + "\n" +
            "  valid = " + text_with_spaces(predicate) + "\n\n" +
            generate_tuple_postamble() + "\n");
  }

  /// Turn SQL projections into Python function definitions
  private String project_def(ParseTree query, ParseTree stream) {
    ParserRuleContext expr_list = (ParserRuleContext)(query.getChild(0).getChild(1));
    ParserRuleContext col_list  = (ParserRuleContext)(query.getChild(0).getChild(5));
    return (spg_query_signature(stream) +
            generate_tuple_preamble() + "\n" +
            "  " + text_with_spaces(col_list) + " = " + text_with_spaces(expr_list) + ";\n\n" +
            generate_tuple_postamble()+ "\n");
  }

  /// Turn SQL joins into Python function definitions
  private String join_def(ParseTree query, ParseTree stream) {  
    return (join_query_signature(stream) +
            "  ret_tuple = Tuple();\n" +
            "  ret_tuple = tuple1.join_tuple(tuple2);\n" +
            "  ret_tuple.valid = tuple1.valid and tuple2.valid\n" +
            "  return ret;\n" + "\n");
  }

  /// Turn SQL GROUPBYs into Python function definitions
  private String groupby_def(ParseTree query, ParseTree stream) {
    ParserRuleContext groupby_list = (ParserRuleContext)(query.getChild(0).getChild(5));
    ParserRuleContext agg_func     = (ParserRuleContext)(query.getChild(0).getChild(1));
    return (spg_query_signature(stream) +
            "  global state_dict;\n\n" +
            generate_tuple_preamble() + "\n" +
            "  key_for_dict = tuple(" + text_with_spaces(groupby_list) + ");\n\n" +
            "  tuple_state = state_dict[key_for_dict] if key_for_dict in state_dict else State();\n\n" +
            "  return " + text_with_spaces(agg_func) + "(tuple_state, tuple_var);" + "\n" + "\n");
  }

  @Override public void exitStream_stmt(perf_queryParser.Stream_stmtContext ctx) {
    ParseTree stream = ctx.getChild(0);
    assert(stream instanceof perf_queryParser.StreamContext);

    ParseTree query = ctx.getChild(2);
    assert(query instanceof perf_queryParser.Stream_queryContext);

    last_assigned_ = text_with_spaces((ParserRuleContext)stream);
    OperationType operation = getOperationType((perf_queryParser.Stream_queryContext)query);
    if (operation == OperationType.FILTER) {
      function_defs_  += filter_def(query, stream);
      function_calls_ += generate_spg_queries(query, stream);
    } else if (operation == OperationType.PROJECT) {
      function_defs_  += project_def(query, stream);
      function_calls_ += generate_spg_queries(query, stream); 
    } else if (operation == OperationType.JOIN) {
      function_defs_  += join_def(query, stream);
      function_calls_ += generate_join_queries(query, stream); 
    } else if (operation == OperationType.SFOLD) {
      function_defs_  += groupby_def(query, stream);
      function_calls_ += generate_spg_queries(query, stream);
    } else {
      assert(false);
    }
  }

  @Override public void exitRelational_stmt(perf_queryParser.Relational_stmtContext ctx) {
    ParseTree stream = ctx.getChild(0);
    assert(stream instanceof perf_queryParser.RelationContext);

    ParseTree query = ctx.getChild(2);
    assert(query instanceof perf_queryParser.Relational_queryContext);

    last_assigned_ = text_with_spaces((ParserRuleContext)stream);
    OperationType operation = getOperationType((perf_queryParser.Relational_queryContext)query);
    if (operation == OperationType.RFOLD) {
      function_defs_  += groupby_def(query, stream);
      function_calls_ += generate_spg_queries(query, stream);
    } else {
      assert(false);
    }
  }

  /// Signature for Python function for select, project, and group by
  private String spg_query_signature(ParseTree stream) {
    String stream_name = text_with_spaces((ParserRuleContext)stream);
    return "def func" + stream_name + "(tuple_var):\n";
  }

  /// Signature for Python functions for join
  private String join_query_signature(ParseTree stream) {
    String stream_name = text_with_spaces((ParserRuleContext)stream);
    return "def func" + stream_name + "(tuple1, tuple2):\n";
  }

  /// Generate Python function calls for select, project, and group by queries
  private String generate_spg_queries(ParseTree query, ParseTree stream) {
    String stream_name = text_with_spaces((ParserRuleContext)stream);
    String arg_name    = text_with_spaces((ParserRuleContext)query.getChild(0).getChild(3));
    return "  " + stream_name + " = func" + stream_name + "(" + arg_name + ")\n";
  }

  /// Generate Python function call for join queries
  private String generate_join_queries(ParseTree query, ParseTree stream) {
    String stream_name = text_with_spaces((ParserRuleContext)stream);
    String arg1   = text_with_spaces((ParserRuleContext)query.getChild(0).getChild(0));
    String arg2   = text_with_spaces((ParserRuleContext)query.getChild(0).getChild(2));
    return "  " + stream_name + " = func" + stream_name + "(" + arg1 + ", " + arg2 + ")\n";
  }

  private String text_with_spaces(ParserRuleContext production) {
    Token start_token = production.getStart();
    Token stop_token  = production.getStop();
    return parser_.getTokenStream().getText(start_token, stop_token);
  }

  private String generate_tuple_class() {
    String ret = "import random;\n";
    ret += "random.seed(1)\n";
    ret += "# tuple class\n";
    ret += "class Tuple: \n";
    ret += "  def __init__(self):\n";
    for (String key : tuple_field_set_) {
      ret = ret + "    " + "self." + key + " = None;\n";
    }
    ret += "    self.state = State();\n";
    ret += "    self.valid = True;\n"; // Everything is valid in the base stream

    ret += "  def print_tuple(self):\n";
    for (String key : tuple_field_set_) {
      ret += "    print \"" + key + "\"," + "self." + key + "\n";
    }
    ret += "    self.state.print_state();\n";

    ret += "  def join_tuple(self, other):\n";
    ret += "    ret = Tuple();\n";
    for (String key : tuple_field_set_) {
      ret += "    ret." + key + " = fuse(self." + key + ", other." + key + ");\n";
    }
    ret += "    return ret;\n";

    return ret;
  }

  private String generate_tuple_preamble() {
    String ret = "  # tuple preamble\n";
    for (String key : tuple_field_set_) {
      ret = ret + "  " + key + " = tuple_var." + key + "\n";
    }
    return ret;
  }

  private String generate_tuple_postamble() {
    String ret = "  # tuple postamble\n";
    for (String key : tuple_field_set_) {
      ret = ret + "  " + "tuple_var." + key + " = " + key + "\n";
    }
    ret += "  return tuple_var;\n";
    return ret;
  }

  private String generate_state_class() {
    String ret = "# state class\n";
    ret += "class State: \n";
    ret += "  def __init__(self):\n";
    // For now, we are assuming all state is init. to 0
    for (String key : state_field_set_) {
      ret = ret + "    " + "self." + key + " = 0;\n";
    }
    ret += "  def print_state(self):\n";
    for (String key : state_field_set_) {
      ret = ret + "    print \"" + key + "\", self." + key +";\n";
    }

    return ret;
  }

  private String emit_stub() {
    String ret = "";
    for (String key : state_field_set_) {
      ret = ret + key.substring(3) + " = " + key + ";";
    }
    ret += "# emit stub";
    return ret;
  }

  private String generate_state_preamble() {
    String ret = "  # state preamble\n";
    for (String key : state_field_set_) {
      ret = ret + "  " + key + " = state." + key + "\n";
    }
    return ret;
  }

  private String generate_state_postamble() {
    String ret = "  # state postamble\n";
    for (String key : state_field_set_) {
      ret = ret + "  " + "state." + key + " = " + key + "\n";
    }
    return ret;
  }

  /// Get operation type for the given query
  private OperationType getOperationType(ParserRuleContext query) {
    assert(query instanceof perf_queryParser.Stream_queryContext ||
           query instanceof perf_queryParser.Relational_queryContext);
    assert(query.getChildCount() == 1);
    ParseTree op = query.getChild(0);
    if (op instanceof perf_queryParser.FilterContext) {
      // SELECT * FROM stream, so stream is at location 3
      return OperationType.FILTER;
    } else if (op instanceof perf_queryParser.SfoldContext) {
      // SELECT agg_func FROM stream SGROUPBY ...
      return OperationType.SFOLD;
    } else if (op instanceof perf_queryParser.ProjectContext) {
      // SELECT expr_list FROM stream
      return OperationType.PROJECT;
    } else if (op instanceof perf_queryParser.JoinContext) {
      // stream JOIN stream
      return OperationType.JOIN;
    } else if (op instanceof perf_queryParser.RfoldContext) {
      return OperationType.RFOLD;
    } else {
      assert(false);
      return OperationType.UNDEFINED;
    }
  }
}
