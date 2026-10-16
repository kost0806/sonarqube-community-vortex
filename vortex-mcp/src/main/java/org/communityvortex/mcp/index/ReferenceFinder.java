package org.communityvortex.mcp.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.declarations.ResolvedFieldDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds usages of a declaration. Java usages are resolved with the JavaParser symbol solver against the
 * workspace's source roots ({@code resolved}); when resolution fails the name match is kept as
 * {@code unresolved}. Other languages use word matches ({@code textual}).
 */
public final class ReferenceFinder {

  public enum Confidence { RESOLVED, UNRESOLVED, TEXTUAL }

  public record Reference(String file, int line, String snippet, Confidence confidence) {
  }

  public record Result(List<Symbol> declarations, List<Reference> references, boolean truncated) {
  }

  private final CodeIndex index;
  private JavaParser resolvingParser;
  private long parserGeneration = -1;

  public ReferenceFinder(CodeIndex index) {
    this.index = index;
  }

  public synchronized Result find(String symbol, int limit) {
    index.refresh();
    var declarations = index.declarations(symbol);
    if (declarations.isEmpty()) {
      return new Result(List.of(), List.of(), false);
    }
    var simpleName = declarations.get(0).name();
    var targets = new LinkedHashSet<String>();
    declarations.forEach(d -> targets.add(d.qualifiedName()));
    // identifier boundaries that also work for names starting or ending with '$'
    var word = Pattern.compile("(?<![\\w$])" + Pattern.quote(simpleName) + "(?![\\w$])");
    var javaTarget = declarations.stream().allMatch(d -> d.file().endsWith(".java"));
    var references = new ArrayList<Reference>();
    // collect one more than requested to know whether results were truncated
    for (var facts : index.allFacts()) {
      if (references.size() > limit) {
        break;
      }
      var isJava = "java".equals(facts.language());
      if (javaTarget && !isJava) {
        // other languages cannot reference Java declarations
        continue;
      }
      var content = read(index.root().resolve(facts.file()));
      if (content.isEmpty() || !word.matcher(content.get()).find()) {
        continue;
      }
      var found = javaTarget
        ? javaReferences(facts.file(), content.get(), simpleName, targets, declarations)
        : textualReferences(facts.file(), content.get(), word, declarations);
      for (var reference : found) {
        if (references.size() <= limit) {
          references.add(reference);
        }
      }
    }
    var truncated = references.size() > limit;
    return new Result(declarations, List.copyOf(truncated ? references.subList(0, limit) : references), truncated);
  }

  private List<Reference> javaReferences(String file, String content, String name, Set<String> targets, List<Symbol> declarations) {
    var parsed = parser().parse(content).getResult();
    if (parsed.isEmpty()) {
      return List.of();
    }
    var lines = content.split("\\R", -1);
    var references = new ArrayList<Reference>();
    var candidates = new ArrayList<Node>();
    parsed.get().findAll(MethodCallExpr.class, n -> n.getNameAsString().equals(name)).forEach(candidates::add);
    parsed.get().findAll(MethodReferenceExpr.class, n -> n.getIdentifier().equals(name)).forEach(candidates::add);
    parsed.get().findAll(NameExpr.class, n -> n.getNameAsString().equals(name)).forEach(candidates::add);
    parsed.get().findAll(FieldAccessExpr.class, n -> n.getNameAsString().equals(name)).forEach(candidates::add);
    parsed.get().findAll(ClassOrInterfaceType.class, n -> n.getNameAsString().equals(name)).forEach(candidates::add);
    parsed.get().findAll(ObjectCreationExpr.class, n -> n.getType().getNameAsString().equals(name)).forEach(candidates::add);
    var seen = new LinkedHashSet<Integer>();
    for (var node : candidates) {
      var line = node.getBegin().map(p -> p.line).orElse(0);
      if (isDeclarationLine(file, line, declarations)) {
        continue;
      }
      var resolved = resolve(node);
      if (resolved.isEmpty() && scopedByOtherType(node, declarations)) {
        // e.g. RuleKey.parse(..) when looking for ArchitectureRules.parse: unresolvable, but clearly another type
        continue;
      }
      var confidence = resolved.map(q -> targets.contains(q) ? Confidence.RESOLVED : null).orElse(Confidence.UNRESOLVED);
      if (confidence != null && seen.add(line)) {
        references.add(new Reference(file, line, snippet(lines, line), confidence));
      }
    }
    references.sort((a, b) -> Integer.compare(a.line(), b.line()));
    return references;
  }

  /** Qualified name of what {@code node} refers to, in the notation of {@link Symbol#qualifiedName()}. */
  private static Optional<String> resolve(Node node) {
    try {
      if (node instanceof MethodCallExpr call) {
        var method = call.resolve();
        return Optional.of(method.declaringType().getQualifiedName() + "." + method.getName());
      }
      if (node instanceof MethodReferenceExpr reference) {
        var method = reference.resolve();
        return Optional.of(method.declaringType().getQualifiedName() + "." + method.getName());
      }
      if (node instanceof ObjectCreationExpr creation) {
        return Optional.of(creation.getType().resolve().asReferenceType().getQualifiedName());
      }
      if (node instanceof ClassOrInterfaceType type) {
        return Optional.of(type.resolve().asReferenceType().getQualifiedName());
      }
      if (node instanceof NameExpr nameExpr) {
        return resolveName(nameExpr);
      }
      if (node instanceof FieldAccessExpr access) {
        return fieldName(access.resolve());
      }
    } catch (RuntimeException | StackOverflowError e) {
      // UnsolvedSymbolException, UnsupportedOperationException, ...: keep as unresolved
    }
    return Optional.empty();
  }

  /** {@code Type.member} where {@code Type} looks like a type name that declares none of the targets. */
  private static boolean scopedByOtherType(Node node, List<Symbol> declarations) {
    Optional<com.github.javaparser.ast.expr.Expression> scope = Optional.empty();
    if (node instanceof MethodCallExpr call) {
      scope = call.getScope();
    } else if (node instanceof FieldAccessExpr access) {
      scope = Optional.of(access.getScope());
    }
    if (scope.isEmpty() || !(scope.get() instanceof NameExpr name) || !Character.isUpperCase(name.getNameAsString().charAt(0))) {
      return false;
    }
    var scopeName = name.getNameAsString();
    return declarations.stream().noneMatch(d -> declaringSimpleName(d).equals(scopeName));
  }

  private static String declaringSimpleName(Symbol declaration) {
    var qualified = declaration.qualifiedName();
    var owner = qualified.substring(0, Math.max(0, qualified.lastIndexOf('.')));
    return owner.substring(owner.lastIndexOf('.') + 1);
  }

  /** A simple name is either a value (local, field) or, as in {@code Order.MAX_LINES}, the type scoping a member access. */
  private static Optional<String> resolveName(NameExpr name) {
    try {
      return fieldName(name.resolve());
    } catch (RuntimeException notAValue) {
      var parent = name.getParentNode().orElse(null);
      if (parent instanceof FieldAccessExpr access && access.getScope() == name
        && access.resolve() instanceof ResolvedFieldDeclaration field) {
        return Optional.of(field.declaringType().getQualifiedName());
      }
      if (parent instanceof MethodCallExpr call && call.getScope().filter(s -> s == name).isPresent()) {
        return Optional.of(call.resolve().declaringType().getQualifiedName());
      }
      throw notAValue;
    }
  }

  private static Optional<String> fieldName(ResolvedValueDeclaration value) {
    if (value instanceof ResolvedFieldDeclaration field) {
      return Optional.of(field.declaringType().getQualifiedName() + "." + field.getName());
    }
    return Optional.of(value.getName());
  }

  private static List<Reference> textualReferences(String file, String content, Pattern word, List<Symbol> declarations) {
    var references = new ArrayList<Reference>();
    var lines = content.split("\\R", -1);
    for (int i = 0; i < lines.length; i++) {
      var line = i + 1;
      if (word.matcher(lines[i]).find() && !isDeclarationLine(file, line, declarations)) {
        references.add(new Reference(file, line, lines[i].strip(), Confidence.TEXTUAL));
      }
    }
    return references;
  }

  private static boolean isDeclarationLine(String file, int line, List<Symbol> declarations) {
    return declarations.stream().anyMatch(d -> d.file().equals(file) && d.line() == line);
  }

  private static String snippet(String[] lines, int line) {
    return line >= 1 && line <= lines.length ? lines[line - 1].strip() : "";
  }

  private JavaParser parser() {
    var generation = index.generation();
    if (resolvingParser == null || generation != parserGeneration) {
      var solver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
      for (var root : sourceRoots()) {
        solver.add(new JavaParserTypeSolver(root));
      }
      resolvingParser = new JavaParser(new ParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
        .setSymbolResolver(new JavaSymbolSolver(solver)));
      parserGeneration = generation;
    }
    return resolvingParser;
  }

  /** Directories that contain Java packages: the file's directory minus its package path. */
  private Set<Path> sourceRoots() {
    var roots = new LinkedHashSet<Path>();
    for (var facts : index.allFacts()) {
      if (!"java".equals(facts.language())) {
        continue;
      }
      var directory = Path.of(facts.file()).getParent();
      var packagePath = facts.unit().isEmpty() ? null : Path.of(facts.unit().replace('.', '/'));
      if (directory == null) {
        roots.add(index.root());
      } else if (packagePath == null) {
        roots.add(index.root().resolve(directory));
      } else if (directory.endsWith(packagePath)) {
        var depth = directory.getNameCount() - packagePath.getNameCount();
        roots.add(depth == 0 ? index.root() : index.root().resolve(directory.subpath(0, depth)));
      }
    }
    return roots;
  }

  private static Optional<String> read(Path file) {
    try {
      if (Files.size(file) > CodeIndex.MAX_FILE_BYTES) {
        return Optional.empty();
      }
      return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return Optional.empty();
    }
  }
}
