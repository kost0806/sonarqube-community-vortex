package org.communityvortex.mcp.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import org.communityvortex.mcp.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeIndexTest {

  @TempDir
  Path root;
  private CodeIndex index;
  private Workspace workspace;

  @BeforeEach
  void setUp() throws Exception {
    SampleWorkspace.create(root);
    workspace = new Workspace(root);
    index = new CodeIndex(workspace);
    index.refresh();
  }

  @Test
  void indexesSupportedLanguagesAndSkipsIgnoredDirectories() {
    assertThat(index.languageCounts()).containsEntry("java", 3).containsEntry("ts", 2).containsEntry("py", 1);
    assertThat(index.allFacts()).extracting(SourceFacts::file).noneMatch(f -> f.startsWith("node_modules"));
  }

  @Test
  void searchRanksExactMatchesFirstAndFiltersByKind() {
    assertThat(index.searchSymbols("order", null, 3)).extracting(Symbol::qualifiedName)
      .startsWith("com.acme.domain.Order");
    assertThat(index.searchSymbols("total", "method", 10)).extracting(Symbol::qualifiedName)
      .containsExactly("com.acme.app.Checkout.total", "com.acme.domain.Order.total");
    assertThat(index.searchSymbols("com.acme.domain.Order.total", null, 10)).hasSize(1);
  }

  @Test
  void declarationsPreferQualifiedMatches() {
    assertThat(index.declarations("com.acme.domain.Order.total")).hasSize(1);
    assertThat(index.declarations("total")).hasSize(2);
    assertThat(index.declarations("nothing")).isEmpty();
  }

  @Test
  void findsImportersPerLanguage() {
    var order = index.facts(root.resolve("src/main/java/com/acme/domain/Order.java")).orElseThrow();
    var client = index.facts(root.resolve("web/src/db/client.ts")).orElseThrow();

    assertThat(index.importers(order)).containsExactly(
      "src/main/java/com/acme/app/Checkout.java", "src/main/java/com/acme/infra/OrderRepository.java");
    assertThat(index.importers(client)).containsExactly("web/src/ui/App.tsx");
  }

  @Test
  void moduleMapAggregatesDependenciesBetweenModules() {
    var modules = index.moduleMap(3, 10);

    assertThat(modules).anySatisfy(m -> {
      assertThat(m.module()).isEqualTo("com.acme.app");
      assertThat(m.dependsOn()).containsEntry("com.acme.domain", 1).containsEntry("com.acme.infra", 1);
    });
    assertThat(index.moduleMap(3, 1)).hasSize(1);
  }

  @Test
  void refreshOnlyReportsRealChanges() throws Exception {
    assertThat(index.refresh()).isFalse();
    var generation = index.generation();

    var file = root.resolve("web/src/db/client.ts");
    Files.writeString(file, "export class DbClient {}\nexport function reconnect() {}\n");
    Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(5)));
    assertThat(index.refresh()).isTrue();
    assertThat(index.generation()).isGreaterThan(generation);
    assertThat(index.searchSymbols("reconnect", null, 5)).hasSize(1);

    Files.delete(root.resolve("py/shop/orders.py"));
    assertThat(index.refresh()).isTrue();
    assertThat(index.languageCounts()).doesNotContainKey("py");
  }

  @Test
  void factsForUnsupportedOrBrokenFiles() throws Exception {
    assertThat(index.facts(root.resolve("README.md"))).isEmpty();
    SampleWorkspace.write(root, "Broken.java", "class {{{");
    assertThat(index.facts(root.resolve("Broken.java"))).isEmpty();
    Files.write(root.resolve("latin1.py"), new byte[] {(byte) 0xE9, (byte) 0xFF});
    assertThat(index.facts(root.resolve("latin1.py"))).isEmpty();
  }
}
