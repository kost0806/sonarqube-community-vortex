package org.communityvortex.mcp.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.nio.file.Path;
import org.communityvortex.mcp.index.ReferenceFinder.Confidence;
import org.communityvortex.mcp.index.ReferenceFinder.Reference;
import org.communityvortex.mcp.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReferenceFinderTest {

  @TempDir
  Path root;
  private ReferenceFinder finder;

  @BeforeEach
  void setUp() throws Exception {
    SampleWorkspace.create(root);
    finder = new ReferenceFinder(new CodeIndex(new Workspace(root)));
  }

  @Test
  void resolvesJavaMethodCallsToTheRightDeclaration() {
    var result = finder.find("com.acme.domain.Order.total", 50);

    assertThat(result.declarations()).hasSize(1);
    // Checkout.total() is a different method with the same name and must not be reported
    assertThat(result.references())
      .extracting(Reference::file, Reference::line, Reference::confidence)
      .containsExactly(tuple("src/main/java/com/acme/app/Checkout.java", 17, Confidence.RESOLVED));
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void resolvesTypeUsagesAcrossFiles() {
    var result = finder.find("com.acme.domain.Order", 50);

    assertThat(result.references()).extracting(Reference::file, Reference::line)
      .contains(
        tuple("src/main/java/com/acme/app/Checkout.java", 15),
        tuple("src/main/java/com/acme/infra/OrderRepository.java", 7));
    assertThat(result.references()).allSatisfy(r -> assertThat(r.confidence()).isEqualTo(Confidence.RESOLVED));
  }

  @Test
  void resolvesStaticFieldAccess() {
    var result = finder.find("com.acme.domain.Order.MAX_LINES", 50);

    assertThat(result.references()).extracting(Reference::line, Reference::confidence)
      .containsExactly(tuple(16, Confidence.RESOLVED));
  }

  @Test
  void skipsUnresolvableCallsOnOtherTypesButKeepsAmbiguousOnes() throws Exception {
    SampleWorkspace.write(root, "src/main/java/com/acme/app/Parsing.java", """
      package com.acme.app;

      import com.acme.domain.Order;
      import org.unknown.Lib;

      class Parsing {
        int a(Order order, Object unknown) {
          Lib.total();
          return ((Order) unknown).total();
        }
        int b(org.unknown.Thing thing) {
          return thing.total();
        }
      }
      """);
    var result = new ReferenceFinder(new CodeIndex(new Workspace(root))).find("com.acme.domain.Order.total", 50);

    assertThat(result.references()).filteredOn(r -> r.file().endsWith("Parsing.java"))
      .extracting(Reference::line, Reference::confidence)
      .containsExactly(tuple(9, Confidence.RESOLVED), tuple(12, Confidence.UNRESOLVED));
  }

  @Test
  void nonJavaSymbolsUseWordMatches() {
    var result = finder.find("connect", 50);

    assertThat(result.references()).extracting(Reference::file, Reference::confidence)
      .contains(tuple("web/src/ui/App.tsx", Confidence.TEXTUAL));
    // the declaration line itself is not a reference
    assertThat(result.references()).noneMatch(r -> r.file().equals("web/src/db/client.ts") && r.line() == 2);
  }

  @Test
  void limitTruncatesAndUnknownSymbolsReturnNothing() {
    assertThat(finder.find("connect", 1).truncated()).isTrue();
    assertThat(finder.find("doesNotExist", 10).declarations()).isEmpty();
  }

  @Test
  void exactlyLimitResultsIsNotTruncated() {
    var all = finder.find("com.acme.domain.Order.total", 50).references().size();

    var result = finder.find("com.acme.domain.Order.total", all);

    assertThat(result.references()).hasSize(all);
    assertThat(result.truncated()).isFalse();
  }

  @Test
  void findsIdentifiersStartingWithDollar() throws Exception {
    SampleWorkspace.write(root, "web/src/jq.ts", "export const $store = {};\n");
    SampleWorkspace.write(root, "web/src/use.ts", "import { $store } from './jq';\nconsole.log($store);\nconst x$store = 1;\n");

    var result = new ReferenceFinder(new CodeIndex(new Workspace(root))).find("$store", 50);

    assertThat(result.references()).extracting(Reference::file, Reference::line)
      .containsExactly(tuple("web/src/use.ts", 1), tuple("web/src/use.ts", 2));
  }
}
