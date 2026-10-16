package org.communityvortex.mcp.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Java declarations and imports via JavaParser. */
public final class JavaSourceIndexer implements SourceIndexer {

  private final JavaParser parser = new JavaParser(new ParserConfiguration()
    .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));

  @Override
  public boolean supports(String relativePath) {
    return relativePath.endsWith(".java");
  }

  @Override
  public synchronized Optional<SourceFacts> index(String relativePath, String content) {
    var result = parser.parse(content);
    // JavaParser recovers from syntax errors; a half-parsed file would yield misleading facts
    if (!result.isSuccessful() || result.getResult().isEmpty()) {
      return Optional.empty();
    }
    var unit = result.getResult().get();
    var packageName = unit.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
    var symbols = new ArrayList<Symbol>();
    for (var type : unit.getTypes()) {
      collectType(type, qualify(packageName, type.getNameAsString()), relativePath, symbols);
    }
    return Optional.of(new SourceFacts(relativePath, "java", packageName, imports(unit), symbols));
  }

  private static List<String> imports(CompilationUnit unit) {
    var imports = new ArrayList<String>();
    for (var declaration : unit.getImports()) {
      // "import a.b.*" targets package a.b; "import static a.B.c" targets type a.B
      if (declaration.isAsterisk() || declaration.isStatic()) {
        imports.add(declaration.isAsterisk() && !declaration.isStatic()
          ? declaration.getNameAsString()
          : declaration.getName().getQualifier().map(q -> q.asString()).orElse(declaration.getNameAsString()));
      } else {
        imports.add(declaration.getNameAsString());
      }
    }
    return imports;
  }

  private static void collectType(TypeDeclaration<?> type, String qualifiedName, String file, List<Symbol> symbols) {
    symbols.add(symbol(type, type.getNameAsString(), qualifiedName, kindOf(type), file, null));
    for (BodyDeclaration<?> member : type.getMembers()) {
      if (member instanceof TypeDeclaration<?> nested) {
        collectType(nested, qualifiedName + "." + nested.getNameAsString(), file, symbols);
      } else if (member instanceof MethodDeclaration method) {
        symbols.add(symbol(method, method.getNameAsString(), qualifiedName + "." + method.getNameAsString(), "method", file,
          signature(method)));
      } else if (member instanceof ConstructorDeclaration constructor) {
        symbols.add(symbol(constructor, constructor.getNameAsString(), qualifiedName + "." + constructor.getNameAsString(),
          "constructor", file, signature(constructor)));
      } else if (member instanceof FieldDeclaration field) {
        for (var variable : field.getVariables()) {
          symbols.add(symbol(variable, variable.getNameAsString(), qualifiedName + "." + variable.getNameAsString(), "field",
            file, variable.getTypeAsString() + " " + variable.getNameAsString()));
        }
      }
    }
  }

  private static String signature(CallableDeclaration<?> callable) {
    return callable.getDeclarationAsString(false, false, true);
  }

  private static String kindOf(TypeDeclaration<?> type) {
    if (type instanceof ClassOrInterfaceDeclaration c) {
      return c.isInterface() ? "interface" : "class";
    }
    if (type instanceof EnumDeclaration) {
      return "enum";
    }
    if (type instanceof RecordDeclaration) {
      return "record";
    }
    if (type instanceof AnnotationDeclaration) {
      return "annotation";
    }
    return "type";
  }

  private static Symbol symbol(Node node, String name, String qualifiedName, String kind, String file, String signature) {
    var begin = node.getBegin().map(p -> p.line).orElse(0);
    var end = node.getEnd().map(p -> p.line).orElse(begin);
    return new Symbol(name, qualifiedName, kind, file, begin, end, signature);
  }

  private static String qualify(String packageName, String name) {
    return packageName.isEmpty() ? name : packageName + "." + name;
  }
}
